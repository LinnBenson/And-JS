package io.todu.andjs.runtime

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID

/** All handles belong to one run. Binder callbacks only copy data into bounded queues. */
@Suppress("MissingPermission", "DEPRECATION")
internal class AndroidBleSession(private val context:Context,private val control:RunControl):BleSession {
    private val watch=context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    private val lock=Any()
    private val handles=mutableSetOf<Resource>()
    private var disposed=false
    private var opened=0
    private var release:(()->Unit)?=null
    init {release=control.registerStop {close()};if(disposed) release?.invoke()}
    private val adapter:BluetoothAdapter get() {
        val a=(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: throw bleError("UNSUPPORTED","此设备不支持蓝牙")
        if(Build.VERSION.SDK_INT>=31) permission(Manifest.permission.BLUETOOTH_CONNECT)
        if(!a.isEnabled) throw bleError("BLUETOOTH_OFF","请开启蓝牙")
        return a
    }
    private fun permission(p:String) {if(context.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) throw bleError("PERMISSION_DENIED","请在权限授权页允许蓝牙和所需的位置权限")}
    private fun number(o:JSONObject,key:String,default:Int,max:Int,min:Int=1):Int {
        if(!o.has(key)) return default
        val n=o.get(key) as? Number ?: throw bleError("INVALID_OPTIONS","$key 须为整数")
        if(!n.toDouble().isFinite() || n.toDouble()%1!=0.0 || n.toDouble()<min || n.toDouble()>max) throw bleError("INVALID_OPTIONS","$key 须在 $min–$max 之间")
        return n.toInt()
    }
    private fun uuid(value:String):UUID {
        val text=when(value.length) {4->"0000$value-0000-1000-8000-00805f9b34fb";8->"$value-0000-1000-8000-00805f9b34fb";else->value}
        require(text.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) {"UUID 无效"}
        return UUID.fromString(text)
    }
    private fun bytes(text:String,encoding:String):ByteArray {
        require(text.length<=2048) {"数据过长"}
        return when(encoding) {
            "text"->text.toByteArray(Charsets.UTF_8)
            "base64"->Base64.getDecoder().decode(text)
            "hex"->{require(text.length%2==0 && text.matches(Regex("[0-9a-fA-F]*"))) {"hex 须为完整字节的十六进制字符串"};ByteArray(text.length/2) {text.substring(it*2,it*2+2).toInt(16).toByte()}}
            else->throw bleError("INVALID_OPTIONS","encoding 须为 text、hex 或 base64")
        }.also {require(it.size<=512) {"单条数据最多 512 字节"}}
    }
    private fun packet(data:ByteArray)=JSONObject().put("base64",Base64.getEncoder().encodeToString(data)).put("hex",data.joinToString("") {"%02x".format(it.toInt() and 255)}).put("text",String(data,Charsets.UTF_8)).put("size",data.size)
    override fun open(kind:String,args:JSONArray):BleHandle {
        val a=adapter
        val options=if(kind=="connect") args.optJSONObject(1) ?: JSONObject() else args.optJSONObject(0) ?: JSONObject()
        val r:Resource=when(kind) {
            "connect"->Connection(a,args.getString(0),options)
            "scan"->{
                if(Build.VERSION.SDK_INT>=31) permission(Manifest.permission.BLUETOOTH_SCAN)
                permission(Manifest.permission.ACCESS_FINE_LOCATION)
                if(LocationApi(context).status()["enabled"]!=true) throw bleError("LOCATION_OFF","请开启系统定位服务")
                Scanner(a,options)
            }
            "advertise"->{if(Build.VERSION.SDK_INT>=31) permission(Manifest.permission.BLUETOOTH_ADVERTISE);Advertiser(a,options)}
            else->throw bleError("INVALID_OPTIONS","未知 BLE 操作")
        }
        synchronized(lock) {
            if(disposed || control.stopped.get()) throw bleError("CANCELLED","脚本已停止")
            if(handles.size>=(if(watch) 3 else 6) || opened>=(if(watch) 16 else 32)) throw bleError("RESOURCE_LIMIT","BLE 资源数量达到本次运行上限")
            handles.add(r);opened++
        }
        try {r.start();ready(r);return r} catch(e:Exception) {r.close();throw e}
    }
    override fun close() {
        val current=synchronized(lock) {if(disposed) return;disposed=true;handles.toList()}
        current.forEach {runCatching {it.close()}}
        release?.invoke()
    }
    private abstract inner class Resource(val kind:String):BleHandle {
        protected val monitor=java.lang.Object()
        protected var closed=false
        protected var failure:BleFailure?=null
        protected var ready=false
        private val queue=ArrayDeque<JSONObject>()
        private var queuedBytes=0
        private var registered=false
        private val stateReceiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context,i:Intent) {
                if(i.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1)!=BluetoothAdapter.STATE_ON) fail(bleError("BLUETOOTH_OFF","蓝牙已关闭"))
            }
        }
        abstract fun begin()
        protected abstract fun cleanup()
        fun start() {
            synchronized(monitor) {
                checkOpen()
                ContextCompat.registerReceiver(context,stateReceiver,IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),ContextCompat.RECEIVER_EXPORTED)
                registered=true
                begin()
            }
        }
        protected fun checkOpen() {
            if(control.stopped.get()) throw bleError("CANCELLED","脚本已停止")
            failure?.let {throw it}
            if(closed) throw bleError("CLOSED","BLE 已关闭")
        }
        protected fun enqueue(event:JSONObject) {
            val size=event.toString().length*2
            var overflow=false
            synchronized(monitor) {
                if(closed || control.stopped.get()) return
                if(queue.size>=(if(watch) 8 else 32) || queuedBytes+size>(if(watch) 32768 else 131072)) overflow=true
                else {queue.add(event);queuedBytes+=size;monitor.notifyAll()}
            }
            if(overflow) fail(bleError("QUEUE_OVERFLOW","BLE 消息队列已满，已关闭；请缩小扫描范围或加快处理"))
        }
        protected fun fail(e:BleFailure) {finish(e)}
        private fun finish(e:BleFailure?) {
            synchronized(monitor) {
                if(closed) return
                closed=true;failure=e;queue.clear();queuedBytes=0;monitor.notifyAll()
                runCatching {cleanup()}
                if(registered) {runCatching {context.unregisterReceiver(stateReceiver)};registered=false}
            }
            synchronized(lock) {handles.remove(this)}
        }
        override fun close() {finish(null)}
        override fun info():JSONObject=synchronized(monitor) {JSONObject().put("kind",kind).put("closed",closed).put("code",failure?.details?.optString("code") ?: if(closed) "CLOSED" else "OPEN")}
        override fun poll(timeoutMs:Long):JSONObject? {
            val deadline=System.nanoTime()+timeoutMs*1000000
            synchronized(monitor) {
                while(true) {
                    checkOpen()
                    if(queue.isNotEmpty()) return queue.removeFirst().also {queuedBytes-=it.toString().length*2}
                    val remaining=(deadline-System.nanoTime())/1000000
                    if(remaining<=0) return null
                    monitor.wait(remaining.coerceAtMost(100))
                }
            }
        }
        protected fun await(timeout:Int,condition:()->Boolean) {
            val deadline=System.nanoTime()+timeout*1000000L
            synchronized(monitor) {
                while(!condition()) {
                    checkOpen()
                    val remaining=(deadline-System.nanoTime())/1000000
                    if(remaining<=0) {val e=bleError("TIMEOUT","BLE 操作超时，已关闭以避免迟到结果混入后续操作");fail(e);throw e}
                    monitor.wait(remaining.coerceAtMost(100))
                }
                checkOpen()
            }
        }
        override fun call(method:String,args:JSONArray):Any?=throw bleError("INVALID_OPTIONS","$kind 不支持 $method")
    }
    private inner class Scanner(private val a:BluetoothAdapter,private val o:JSONObject):Resource("scan") {
        private var scanner:BluetoothLeScanner?=null
        private var started=false
        private val lastSeen=linkedMapOf<String,Long>()
        private val interval=number(o,"interval",1000,60000,0)
        private val callback=object:ScanCallback() {
            override fun onScanFailed(code:Int) {fail(bleError("SCAN_FAILED","BLE 扫描失败",code))}
            override fun onScanResult(type:Int,r:ScanResult) {
                try {
                    synchronized(monitor) {
                        if(closed) return
                        val now=android.os.SystemClock.elapsedRealtime()
                        val address=r.device.address
                        if(now-(lastSeen[address] ?: -60001)<interval) return
                        if(lastSeen.size>=128 && address !in lastSeen) lastSeen.remove(lastSeen.keys.first())
                        lastSeen[address]=now
                        val record=r.scanRecord
                        val data=record?.bytes ?: ByteArray(0)
                        if(data.size>1650) {fail(bleError("MESSAGE_TOO_LARGE","BLE 广播数据过长"));return}
                        enqueue(packet(data).put("type","advertisement").put("address",address).put("name",record?.deviceName ?: JSONObject.NULL)
                            .put("rssi",r.rssi).put("serviceUuids",JSONArray(record?.serviceUuids.orEmpty().map {it.toString()})))
                    }
                } catch(e:Exception) {fail(bleError("SCAN_FAILED",e.message ?: "读取广播失败"))}
            }
        }
        override fun begin() {
            val filter=ScanFilter.Builder()
            o.optString("address").takeIf {it.isNotEmpty()}?.let {require(BluetoothAdapter.checkBluetoothAddress(it)) {"蓝牙地址无效"};filter.setDeviceAddress(it)}
            o.optString("service").takeIf {it.isNotEmpty()}?.let {filter.setServiceUuid(ParcelUuid(uuid(it)))}
            scanner=a.bluetoothLeScanner ?: throw bleError("UNSUPPORTED","BLE 扫描不可用")
            started=true
            scanner!!.startScan(listOf(filter.build()),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),callback)
            ready=true
        }
        override fun cleanup() {if(started) scanner?.stopScan(callback);lastSeen.clear()}
    }
    private inner class Advertiser(private val a:BluetoothAdapter,private val o:JSONObject):Resource("advertise") {
        private var advertiser:BluetoothLeAdvertiser?=null
        private var started=false
        private val callback=object:AdvertiseCallback() {
            override fun onStartSuccess(settings:AdvertiseSettings) {synchronized(monitor) {if(closed) {runCatching {advertiser?.stopAdvertising(this)};return};ready=true;monitor.notifyAll()}}
            override fun onStartFailure(code:Int) {fail(bleError("ADVERTISE_FAILED","无法发送广播；请检查硬件能力和广播大小",code))}
        }
        override fun begin() {
            val data=AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            val service=o.optString("service")
            val manufacturer=if(o.has("manufacturerId")) number(o,"manufacturerId",0,65535,0) else null
            require(service.isNotEmpty() || manufacturer!=null) {"需要 service 或 manufacturerId"}
            require(service.isEmpty() || manufacturer==null) {"service 与 manufacturerId 二选一"}
            val value=bytes(o.optString("data",""),o.optString("encoding","hex"))
            if(service.isNotEmpty()) {val id=ParcelUuid(uuid(service));data.addServiceUuid(id).addServiceData(id,value)} else data.addManufacturerData(manufacturer!!,value)
            advertiser=a.bluetoothLeAdvertiser ?: throw bleError("UNSUPPORTED","此设备不支持 BLE 广播")
            started=true
            advertiser!!.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(false).setTimeout(0).build(),data.build(),callback)
        }
        fun waitReady() {await(10000) {ready}}
        override fun cleanup() {if(started) advertiser?.stopAdvertising(callback)}
    }
    private inner class Connection(private val a:BluetoothAdapter,private val address:String,private val o:JSONObject):Resource("connection") {
        private var gatt:BluetoothGatt?=null
        private var mtu=23
        private val timeout=number(o,"timeout",10000,15000)
        private var pending:String?=null
        private var value:Any?=null
        private var operationError:BleFailure?=null
        private val cccd=uuid("2902")
        private fun key(c:BluetoothGattCharacteristic)=c.service.uuid.toString()+"/"+c.uuid.toString()
        private fun complete(name:String,status:Int,result:Any?=true) {synchronized(monitor) {
            if(closed || pending!=name) return
            value=result;operationError=if(status==BluetoothGatt.GATT_SUCCESS) null else bleError("GATT_ERROR","GATT 操作失败",status)
            pending=null;monitor.notifyAll()
        }}
        private fun message(c:BluetoothGattCharacteristic,data:ByteArray) {
            if(data.size>512) {fail(bleError("MESSAGE_TOO_LARGE","特征值超过 512 字节"));return}
            enqueue(packet(data.copyOf()).put("type","notification").put("service",c.service.uuid.toString()).put("characteristic",c.uuid.toString()).put("address",address))
        }
        private val callback=object:BluetoothGattCallback() {
            override fun onConnectionStateChange(g:BluetoothGatt,status:Int,state:Int) {synchronized(monitor) {
                if(closed) {runCatching {g.close()};return}
                if(status!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED) {fail(bleError("DISCONNECTED","BLE 连接已断开",status));return}
                if(state==BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) fail(bleError("GATT_ERROR","无法发现 GATT 服务"))
            }}
            override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {synchronized(monitor) {
                if(closed) return
                if(status!=BluetoothGatt.GATT_SUCCESS) {fail(bleError("GATT_ERROR","发现服务失败",status));return}
                ready=true;monitor.notifyAll()
            }}
            override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic) {if(Build.VERSION.SDK_INT<33) message(c,c.value ?: ByteArray(0))}
            override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,data:ByteArray) {message(c,data)}
            override fun onCharacteristicRead(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int) {if(Build.VERSION.SDK_INT<33) complete("read/"+key(c),status,packet(c.value?.copyOf() ?: ByteArray(0)))}
            override fun onCharacteristicRead(g:BluetoothGatt,c:BluetoothGattCharacteristic,data:ByteArray,status:Int) {complete("read/"+key(c),status,packet(data.copyOf()))}
            override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int) {complete("write/"+key(c),status)}
            override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int) {complete("subscribe/"+key(d.characteristic),status)}
            override fun onMtuChanged(g:BluetoothGatt,newMtu:Int,status:Int) {synchronized(monitor) {if(!closed && status==BluetoothGatt.GATT_SUCCESS) mtu=newMtu.coerceIn(23,517)};complete("mtu",status,mtu)}
        }
        override fun begin() {
            require(BluetoothAdapter.checkBluetoothAddress(address)) {"蓝牙地址无效"}
            gatt=a.getRemoteDevice(address).connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE) ?: throw bleError("CONNECT_FAILED","无法建立 BLE 连接")
        }
        fun waitReady() {await(timeout) {ready}}
        private fun operation(name:String,start:()->Boolean):Any? {
            synchronized(monitor) {
                checkOpen();check(pending==null) {"已有 GATT 操作正在进行"};pending=name;value=null;operationError=null
                try {if(!start()) {pending=null;throw bleError("GATT_BUSY","设备未接受 GATT 操作")}} catch(e:Exception) {pending=null;throw e}
            }
            await(timeout) {pending==null}
            return synchronized(monitor) {operationError?.let {throw it};value}
        }
        private fun characteristic(args:JSONArray):BluetoothGattCharacteristic {
            val service=gatt!!.services.filter {it.uuid==uuid(args.getString(0))}
            if(service.size!=1) throw bleError("NOT_FOUND","服务不存在或 UUID 不唯一")
            val chars=service.single().characteristics.filter {it.uuid==uuid(args.getString(1))}
            if(chars.size!=1) throw bleError("NOT_FOUND","特征值不存在或 UUID 不唯一")
            return chars.single()
        }
        override fun call(method:String,args:JSONArray):Any? {
            synchronized(monitor) {checkOpen()}
            val g=gatt ?: throw bleError("CLOSED","连接已关闭")
            if(method=="services") return JSONArray().apply {g.services.forEach {s->put(JSONObject().put("uuid",s.uuid.toString()).put("characteristics",JSONArray().apply {s.characteristics.forEach {c->put(JSONObject().put("uuid",c.uuid.toString()).put("properties",c.properties).put("read",c.properties and BluetoothGattCharacteristic.PROPERTY_READ!=0).put("write",c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE!=0).put("writeWithoutResponse",c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE!=0).put("notify",c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0).put("indicate",c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE!=0))}}))}}
            if(method=="mtu") return operation("mtu") {g.requestMtu(number(JSONObject().put("mtu",args.get(0)),"mtu",23,517,23))}
            val c=characteristic(args)
            return when(method) {
                "read"->{require(c.properties and BluetoothGattCharacteristic.PROPERTY_READ!=0) {"特征值不支持读取"};operation("read/"+key(c)) {g.readCharacteristic(c)}}
                "send","write"->{
                    val options=args.optJSONObject(3) ?: JSONObject()
                    val data=bytes(args.getString(2),options.optString("encoding","text"))
                    require(data.size<=minOf(512,mtu-3)) {"数据超过当前 MTU 允许的 ${minOf(512,mtu-3)} 字节；请按设备协议分包或先协商 mtu"}
                    require(!options.has("withoutResponse") || options.get("withoutResponse") is Boolean) {"withoutResponse 须为 boolean"}
                    val noResponse=options.optBoolean("withoutResponse",false)
                    require(c.properties and (if(noResponse) BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE else BluetoothGattCharacteristic.PROPERTY_WRITE)!=0) {"特征值不支持此写入方式"}
                    val type=if(noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    operation("write/"+key(c)) {if(Build.VERSION.SDK_INT>=33) g.writeCharacteristic(c,data,type)==BluetoothStatusCodes.SUCCESS else {c.writeType=type;c.value=data;g.writeCharacteristic(c)}}
                }
                "subscribe","unsubscribe"->{
                    val enabled=method=="subscribe"
                    val indicate=if(args.length()>2) {require(args.get(2) is Boolean) {"indicate 须为 boolean"};args.getBoolean(2)} else c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY==0
                    if(enabled) require(c.properties and (if(indicate) BluetoothGattCharacteristic.PROPERTY_INDICATE else BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0) {"特征值不支持此订阅方式"}
                    val descriptor=c.getDescriptor(cccd) ?: throw bleError("NOT_FOUND","特征值没有 CCCD 描述符")
                    check(g.setCharacteristicNotification(c,enabled)) {"无法设置通知监听"}
                    try {operation("subscribe/"+key(c)) {
                        val data=if(!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE else if(indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if(Build.VERSION.SDK_INT>=33) g.writeDescriptor(descriptor,data)==BluetoothStatusCodes.SUCCESS else {descriptor.value=data;g.writeDescriptor(descriptor)}
                    }} catch(e:Exception) {runCatching {g.setCharacteristicNotification(c,false)};throw e}
                }
                else->throw bleError("INVALID_OPTIONS","未知连接操作")
            }
        }
        override fun cleanup() {gatt?.let {runCatching {it.disconnect()};it.close()};gatt=null;pending=null}
    }
    fun ready(handle:BleHandle) {when(handle) {is Connection->handle.waitReady();is Advertiser->handle.waitReady()}}
}
