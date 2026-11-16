package io.todu.andjs.runtime

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.hardware.camera2.*
import android.net.*
import android.nfc.*
import android.nfc.tech.Ndef
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Script-owned listeners; Android callbacks never invoke Rhino. */
@Suppress("DEPRECATION", "MissingPermission")
internal class AndroidDeviceSession(private val context:Context,private val control:RunControl):DeviceSession {
    companion object {
        private val leases=ConcurrentHashMap.newKeySet<String>()
        private val watchdog=ScheduledThreadPoolExecutor(1) {r->Thread(r,"AndJS-NFC-timeout").apply {isDaemon=true}}.apply {removeOnCancelPolicy=true}
        private val sensorTypes=mapOf("accelerometer" to Sensor.TYPE_ACCELEROMETER,"gyroscope" to Sensor.TYPE_GYROSCOPE,"magnetic" to Sensor.TYPE_MAGNETIC_FIELD,"gravity" to Sensor.TYPE_GRAVITY,"linearAcceleration" to Sensor.TYPE_LINEAR_ACCELERATION,"rotation" to Sensor.TYPE_ROTATION_VECTOR,"light" to Sensor.TYPE_LIGHT,"pressure" to Sensor.TYPE_PRESSURE,"proximity" to Sensor.TYPE_PROXIMITY,"temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,"humidity" to Sensor.TYPE_RELATIVE_HUMIDITY)
    }
    private val watch=context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    private val main=Handler(Looper.getMainLooper())
    private val lock=Any()
    private val resources=mutableSetOf<Resource>()
    private var closed=false
    private var opened=0
    private var unregister:(()->Unit)?=null
    init {unregister=control.registerStop {close()};if(closed) unregister?.invoke()}
    private fun checkRun() {if(control.stopped.get()) throw deviceError("CANCELLED","脚本已停止")}
    private fun number(o:JSONObject,key:String,default:Int,min:Int,max:Int):Int {
        if(!o.has(key)) return default
        val n=o.get(key) as? Number ?: throw deviceError("INVALID_OPTIONS","$key 须为整数")
        require(n.toDouble().isFinite() && n.toDouble()%1==0.0 && n.toDouble() in min.toDouble()..max.toDouble()) {"$key 须为 $min–$max 的整数"}
        return n.toInt()
    }
    private fun onMain(action:()->Unit) {
        if(Looper.myLooper()==Looper.getMainLooper()) {action();return}
        val task=FutureTask<Unit> {action();Unit};main.post(task)
        try {task.get(5000,TimeUnit.MILLISECONDS)} catch(e:Exception) {task.cancel(false);main.removeCallbacks(task);throw (e.cause as? Exception ?: e)}
    }
    override fun open(kind:String,options:JSONObject):BleHandle {
        checkRun()
        val r=when(kind) {"sensors"->Sensors(options);"network"->Network();"torch"->Torch(options);"nfc"->Nfc();"dnd"->Dnd(options);else->throw deviceError("INVALID_OPTIONS","未知设备接口")}
        synchronized(lock) {
            if(closed) throw deviceError("CLOSED","会话已结束")
            check(resources.size<(if(watch) 6 else 12) && opened<64) {"设备监听数量已达上限"}
            resources.add(r);opened++
        }
        try {r.begin();return r} catch(e:Exception) {r.close();throw e}
    }
    override fun close() {
        val all=synchronized(lock) {if(closed) return;closed=true;resources.toList()}
        all.forEach {runCatching {it.close()}};unregister?.invoke()
    }
    override fun status(kind:String):JSONObject {
        checkRun()
        return when(kind) {
            "sensors"->{val manager=context.getSystemService(SensorManager::class.java);JSONObject().put("types",JSONArray(sensorTypes.filter {manager.getDefaultSensor(it.value)!=null}.keys))}
            "network"->JSONObject(ConnectivityApi(context).network())
            "torch"->{val cameras=context.getSystemService(CameraManager::class.java);JSONObject().put("available",cameras.cameraIdList.any {cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)==true}).put("granted",context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
            "nfc"->{val a=NfcAdapter.getDefaultAdapter(context);JSONObject().put("available",a!=null).put("enabled",a?.isEnabled ?: false).put("foreground",ForegroundActivity.get()!=null)}
            "dnd"->{val n=context.getSystemService(NotificationManager::class.java);JSONObject().put("granted",n.isNotificationPolicyAccessGranted).put("filter",n.currentInterruptionFilter)}
            else->throw deviceError("INVALID_OPTIONS","未知设备接口")
        }
    }
    private abstract inner class Resource(private val kind:String):BleHandle {
        protected val monitor=java.lang.Object()
        @Volatile protected var ended=false
        private var failure:DeviceFailure?=null
        private val queue=ArrayDeque<JSONObject>()
        private var bytes=0
        private var leased=false
        protected fun checkOpen() {checkRun();failure?.let {throw it};if(ended) throw deviceError("CLOSED","监听已关闭")}
        protected fun lease() {if(!leases.add(kind)) throw deviceError("BUSY","已有 $kind 操作正在运行");leased=true}
        abstract fun begin()
        protected abstract fun cleanup()
        protected fun emit(event:JSONObject,latest:Boolean=false) {
            var overflow=false
            synchronized(monitor) {
                if(ended || control.stopped.get()) return
                if(latest) {queue.clear();bytes=0}
                val size=event.toString().length*2
                if(queue.size>=(if(watch) 8 else 32) || bytes+size>(if(watch) 32768 else 131072)) overflow=true
                else {queue.addLast(event);bytes+=size;monitor.notifyAll()}
            }
            if(overflow) fail(deviceError("QUEUE_OVERFLOW","设备事件队列已满，监听已关闭"))
        }
        protected fun fail(e:DeviceFailure) {finish(e)}
        private fun finish(e:DeviceFailure?) {
            synchronized(monitor) {if(ended) return;ended=true;failure=e;queue.clear();bytes=0;monitor.notifyAll()}
            try {cleanup()} finally {if(leased) leases.remove(kind);synchronized(lock) {resources.remove(this)}}
        }
        override fun close() {finish(null)}
        override fun info()=synchronized(monitor) {JSONObject().put("kind",kind).put("closed",ended).put("code",failure?.details?.optString("code") ?: if(ended) "CLOSED" else "OPEN")}
        override fun poll(timeoutMs:Long):JSONObject? {
            val until=System.nanoTime()+timeoutMs*1000000
            synchronized(monitor) {while(true) {
                checkOpen()
                if(queue.isNotEmpty()) return queue.removeFirst().also {bytes-=it.toString().length*2}
                val left=(until-System.nanoTime())/1000000
                if(left<=0) return null
                monitor.wait(left.coerceAtMost(100))
            }}
        }
        override fun call(method:String,args:JSONArray):Any?=throw deviceError("INVALID_OPTIONS","不支持 $method")
    }
    private inner class Sensors(o:JSONObject):Resource("sensors"),SensorEventListener {
        private val manager=context.getSystemService(SensorManager::class.java)
        private val type=o.optString("type","accelerometer")
        private val sensor=manager.getDefaultSensor(sensorTypes[type] ?: throw deviceError("INVALID_OPTIONS","不支持的传感器类型")) ?: throw deviceError("UNSUPPORTED","本机没有 $type 传感器")
        private val interval=number(o,"interval",if(watch) 200 else 100,if(watch) 100 else 20,60000)
        private var last=0L
        override fun begin() {synchronized(monitor) {checkOpen();if(!manager.registerListener(this,sensor,interval*1000,main)) throw deviceError("UNSUPPORTED","无法监听此传感器")}}
        override fun onSensorChanged(e:SensorEvent) {
            if(ended || e.timestamp-last<interval*1000000L) return
            last=e.timestamp
            val values=JSONArray();e.values.take(16).forEach {values.put(if(it.isFinite()) it.toDouble() else JSONObject.NULL)}
            emit(JSONObject().put("type",type).put("values",values).put("accuracy",e.accuracy).put("timestampNanos",e.timestamp),latest=true)
        }
        override fun onAccuracyChanged(s:Sensor,accuracy:Int) {}
        override fun cleanup() {manager.unregisterListener(this)}
    }
    private inner class Network:Resource("network") {
        private val manager=context.getSystemService(ConnectivityManager::class.java)
        private var registered=false
        private fun update(event:String) {if(ended) return;try {emit(JSONObject(ConnectivityApi(context).network()).put("event",event),true)} catch(e:Exception) {fail(deviceError("NETWORK_ERROR",e.message ?: "读取网络状态失败"))}}
        private val callback=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n:android.net.Network) {update("available")}
            override fun onLost(n:android.net.Network) {update("lost")}
            override fun onCapabilitiesChanged(n:android.net.Network,c:NetworkCapabilities) {update("changed")}
            override fun onLinkPropertiesChanged(n:android.net.Network,p:LinkProperties) {update("changed")}
        }
        override fun begin() {synchronized(monitor) {checkOpen();manager.registerDefaultNetworkCallback(callback,main);registered=true};update("initial")}
        override fun cleanup() {if(registered) {manager.unregisterNetworkCallback(callback);registered=false}}
    }
    private inner class Torch(private val o:JSONObject):Resource("torch") {
        private val manager=context.getSystemService(CameraManager::class.java)
        private var camera:String?=null
        private var enabled=false
        override fun begin() {synchronized(monitor) {
            checkOpen();lease()
            if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) throw deviceError("PERMISSION_DENIED","请先授予相机权限")
            val available=manager.cameraIdList.filter {manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)==true}
            camera=if(o.has("cameraId")) o.getString("cameraId").also {require(it in available) {"此摄像头没有闪光灯"}} else available.firstOrNull() ?: throw deviceError("UNSUPPORTED","本机没有可用闪光灯")
            set(true)
        }}
        private fun set(value:Boolean) {synchronized(monitor) {checkOpen();manager.setTorchMode(camera!!,value);enabled=value}}
        override fun call(method:String,args:JSONArray):Any? {require(method=="set") {"仅支持 set(boolean)"};require(args.get(0) is Boolean) {"需要 boolean"};set(args.getBoolean(0));return true}
        override fun cleanup() {synchronized(monitor) {if(enabled) {manager.setTorchMode(camera!!,false);enabled=false}}}
    }
    private inner class Nfc:Resource("nfc") {
        private var activity:Activity?=null
        private var adapter:NfcAdapter?=null
        private var reading=false
        private var tag:Tag?=null
        private var current:Ndef?=null
        private fun hex(data:ByteArray)=data.joinToString("") {"%02x".format(it.toInt() and 255)}
        private val reader=NfcAdapter.ReaderCallback {found->
            try {
                synchronized(monitor) {
                    if(ended || control.stopped.get()) return@ReaderCallback
                    tag=found
                    val ndef=Ndef.get(found)
                    val message=ndef?.cachedNdefMessage
                    if((message?.toByteArray()?.size ?: 0)>(if(watch) 4096 else 16384)) {emit(JSONObject().put("id",hex(found.id)).put("error","MESSAGE_TOO_LARGE"));return@ReaderCallback}
                    val records=JSONArray()
                    message?.records?.take(32)?.forEach {r->
                        val item=JSONObject().put("tnf",r.tnf.toInt()).put("type",Base64.getEncoder().encodeToString(r.type)).put("payload",Base64.getEncoder().encodeToString(r.payload))
                        if(r.tnf==NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) && r.payload.isNotEmpty()) {
                            val flags=r.payload[0].toInt() and 255;val offset=1+(flags and 63)
                            if(offset<=r.payload.size) item.put("text",String(r.payload,offset,r.payload.size-offset,if(flags and 128==0) Charsets.UTF_8 else Charsets.UTF_16))
                        }
                        r.toUri()?.let {item.put("uri",it.toString())};records.put(item)
                    }
                    emit(JSONObject().put("type","tag").put("id",hex(found.id)).put("technologies",JSONArray(found.techList.toList())).put("ndef",ndef!=null).put("writable",ndef?.isWritable ?: false).put("maxSize",ndef?.maxSize ?: 0).put("records",records),true)
                }
            } catch(e:Exception) {if(!ended) emit(JSONObject().put("type","error").put("message",e.message ?: "标签读取失败"))}
        }
        override fun begin() {
            synchronized(monitor) {checkOpen();lease()}
            onMain {synchronized(monitor) {
                checkOpen()
                activity=ForegroundActivity.get() ?: throw deviceError("FOREGROUND_REQUIRED","请在应用前台页面使用 NFC")
                adapter=NfcAdapter.getDefaultAdapter(activity) ?: throw deviceError("UNSUPPORTED","本机没有 NFC 读写能力")
                if(adapter?.isEnabled!=true) throw deviceError("DISABLED","请开启 NFC")
                adapter!!.enableReaderMode(activity,reader,NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE,null)
                reading=true
            }}
        }
        override fun call(method:String,args:JSONArray):Any? {
            require(method=="write") {"仅支持 write(id, text, options)"}
            val id=args.getString(0);val text=args.getString(1);val o=args.optJSONObject(2) ?: JSONObject()
            require(text.toByteArray(Charsets.UTF_8).size<=(if(watch) 2048 else 8192)) {"写入内容过长"}
            val record=when(o.optString("type","text")) {"text"->NdefRecord.createTextRecord("en",text);"uri"->{require(Uri.parse(text).scheme!=null) {"URI 必须含协议"};NdefRecord.createUri(text)};else->throw deviceError("INVALID_OPTIONS","type 须为 text 或 uri")}
            val message=NdefMessage(arrayOf(record))
            val timeout=number(o,"timeout",5000,1,10000)
            val tech=synchronized(monitor) {
                checkOpen();check(current==null) {"已有标签写入正在进行"}
                val found=tag ?: throw deviceError("NOT_FOUND","请先贴近标签")
                if(hex(found.id)!=id) throw deviceError("TAG_CHANGED","标签已改变，请使用当前事件中的 id")
                (Ndef.get(found) ?: throw deviceError("UNSUPPORTED","仅支持已格式化的 NDEF 标签")).also {current=it}
            }
            val expired=AtomicBoolean(false)
            val timer=watchdog.schedule({expired.set(true);runCatching {tech.close()}},timeout.toLong(),TimeUnit.MILLISECONDS)
            try {
                checkOpen();tech.connect();checkOpen()
                if(expired.get()) throw deviceError("TIMEOUT","标签写入超时")
                check(tech.isWritable) {"标签只读"};require(message.toByteArray().size<=tech.maxSize) {"标签容量不足"}
                tech.writeNdefMessage(message);checkOpen()
                if(expired.get()) throw deviceError("TIMEOUT","标签写入超时，需重新读取确认结果")
                return true
            } catch(e:Exception) {if(expired.get()) throw deviceError("TIMEOUT","标签写入超时，需重新读取确认结果");throw e}
            finally {timer.cancel(false);runCatching {tech.close()};synchronized(monitor) {if(current===tech) current=null}}
        }
        override fun cleanup() {
            val io=synchronized(monitor) {tag=null;current.also {current=null}}
            runCatching {io?.close()}
            // Never block the UI thread on itself; pending enables recheck ended before touching NFC.
            val stop={if(reading) {runCatching {adapter?.disableReaderMode(activity)};reading=false};activity=null}
            if(Looper.myLooper()==Looper.getMainLooper()) stop() else main.post {stop()}
        }
    }
    private inner class Dnd(private val o:JSONObject):Resource("dnd") {
        private var token:ScriptDnd.Token?=null
        override fun begin() {synchronized(monitor) {checkOpen();lease();token=ScriptDnd.start(context,o.optString("mode","priority"))}}
        override fun cleanup() {synchronized(monitor) {token?.let {ScriptDnd.stop(context,it)};token=null}}
    }
}
