package io.todu.andjs.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Process-wide ownership; scripts never receive platform handles. */
@Suppress("DEPRECATION", "MissingPermission")
internal object AndroidHotspots {
    private val lock=Any()
    private val requests=java.util.concurrent.ConcurrentHashMap<String,HotspotRequest>()
    private var stopping=false
    private fun wifi(context:Context)=context.getSystemService(WifiManager::class.java)
        ?: throw HotspotFailure("UNSUPPORTED","设备没有 Wi-Fi 服务")
    private fun granted(context:Context,p:String)=context.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED
    private fun prune() {requests.entries.removeAll {it.value.snapshot()["finished"]==true}}
    fun call(context:Context,method:String):Map<String,Any?> = when(method) {
        "getHotspotCapabilities"->mapOf("wifi" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
            "localOnly" to "unknown", "customConfiguration" to supportsConfiguration(),
            "permissionGranted" to granted(context,if(Build.VERSION.SDK_INT>=33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION),
            "internet" to if(Build.VERSION.SDK_INT<36) "unsupported" else if(granted(context,"android.permission.TETHER_PRIVILEGED")) "available" else "permissionRequired",
            "deviceControl" to "bestEffort", "deviceInspection" to deviceState(context)["status"],
            "events" to mapOf("onStart" to true,"onStop" to true,"onError" to true,"onClientsChanged" to false,"onInternetChanged" to false))
        "getHotspotState","checkHotspotLeaks"->{
            val items=synchronized(lock){prune();requests.values.map {it.snapshot()}}
            val device=deviceState(context)
            val residual=items.filter {it["residual"]==true}
            mapOf("requests" to items,"device" to device,"residuals" to residual,"hasResiduals" to residual.isNotEmpty(),
                "complete" to false,"appInspectionComplete" to true,"deviceStateKnown" to (device["status"]!="unknown"),
                "limitation" to "系统不提供所有应用热点申请的完整清单", "externalHotspotPossible" to (items.isEmpty() && device["status"]=="started"))
        }
        "stopHotspot"->stopAll(context)
        else->throw IllegalArgumentException("未知热点接口")
    }
    fun start(context:Context,options:HotspotOptions,control:RunControl):HotspotSession {
        if(control.stopped.get()) throw HotspotFailure("CANCELLED","脚本已停止")
        val manager=wifi(context)
        if(options.internet && Build.VERSION.SDK_INT<36) throw HotspotFailure("UNSUPPORTED","互联网共享需要 Android 16 及系统网络共享权限")
        if(!options.internet) {
            val permission=if(Build.VERSION.SDK_INT>=33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
            if(!granted(context,permission)) throw HotspotFailure("PERMISSION_DENIED","请先在权限授权页授予热点所需的附近设备或精确位置权限")
            if(Build.VERSION.SDK_INT<33 && !context.getSystemService(LocationManager::class.java).isLocationEnabledCompat())
                throw HotspotFailure("LOCATION_DISABLED","请先打开系统定位")
        }
        if((options.name!=null || options.password!=null) && !supportsConfiguration())
            throw HotspotFailure("CUSTOM_CONFIG_UNSUPPORTED","当前系统不支持自定义热点名称和密码；请省略 name 和 password")
        val request=HotspotRequest(control,options.internet) {requests.remove(it.id,it)}
        synchronized(lock) {
            prune()
            if(stopping || requests.isNotEmpty()) throw HotspotFailure("BUSY","已有热点请求或待清理项，请先停止并检查热点")
            requests[request.id]=request
            request.attach()
            try {
                if(control.stopped.get()) {request.failed(HotspotFailure("CANCELLED","脚本已停止"))}
                else if(options.internet) InternetHotspot.start(context,request,if(options.name!=null || options.password!=null) configuration(options) else null)
                else {
                    val callback=object:WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(reservation:WifiManager.LocalOnlyHotspotReservation) {
                            try {
                                val name:String?;val password:String?
                                if(Build.VERSION.SDK_INT>=30) {name=reservation.softApConfiguration.ssid;password=reservation.softApConfiguration.passphrase}
                                else {name=reservation.wifiConfiguration?.SSID;password=reservation.wifiConfiguration?.preSharedKey}
                                request.started(mapOf("name" to name,"password" to password,"internetAvailable" to false),{reservation.close()})
                            } catch(e:Exception) {runCatching {reservation.close()};request.failed(failure(e))}
                        }
                        override fun onFailed(reason:Int) {request.failed(HotspotFailure("START_FAILED_$reason",when(reason) {
                            ERROR_NO_CHANNEL->"没有可用信道";ERROR_INCOMPATIBLE_MODE->"当前 Wi-Fi 模式冲突";ERROR_TETHERING_DISALLOWED->"系统禁止开启热点";else->"系统未能启动热点"
                        }))}
                        override fun onStopped() {request.platformStopped()}
                    }
                    // Keep the pending callback registered: cancelling the hidden WifiManager tracker
                    // can swallow its terminal event. A cancelled request releases a late reservation.
                    if(Build.VERSION.SDK_INT>=36 && (options.name!=null || options.password!=null))
                        manager.startLocalOnlyHotspotWithConfiguration(configuration(options),context.mainExecutor,callback)
                    else manager.startLocalOnlyHotspot(callback,Handler(Looper.getMainLooper()))
                }
            } catch(e:Exception) {request.failed(failure(e))}
        }
        val deadline=System.nanoTime()+30_000_000_000L
        try {while(request.state=="starting" && !control.stopped.get() && System.nanoTime()<deadline) Thread.sleep(50)}
        catch(e:InterruptedException) {request.close();Thread.currentThread().interrupt();throw HotspotFailure("CANCELLED","热点等待被中断")}
        if(control.stopped.get() || request.state=="stopping") {request.close();throw HotspotFailure("CANCELLED","热点启动已取消")}
        request.failure()?.let {throw it}
        if(request.state!="started") {request.close();throw HotspotFailure("START_TIMEOUT","热点启动超时或已停止；请检查遗留项")}
        return request
    }
    private fun LocationManager.isLocationEnabledCompat()=if(Build.VERSION.SDK_INT>=28) isLocationEnabled else
        isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    private fun supportsConfiguration():Boolean = Build.VERSION.SDK_INT>=36 && runCatching {configuration(HotspotOptions("AndJS", "12345678"));true}.getOrDefault(false)
    internal fun configuration(options:HotspotOptions):SoftApConfiguration {
        val builder=SoftApConfiguration.Builder()
        // These setters became public in 36.1. Reflection keeps the existing SDK 36 build compatible;
        // no hidden-API exemption is requested, and denial is reported to the caller.
        builder.javaClass.getMethod("setWifiSsid",android.net.wifi.WifiSsid::class.java).invoke(builder,
            android.net.wifi.WifiSsid.fromBytes((options.name ?: "AndJS-${java.util.UUID.randomUUID().toString().take(8)}").toByteArray(Charsets.UTF_8)))
        builder.javaClass.getMethod("setPassphrase",String::class.java,Int::class.javaPrimitiveType).invoke(builder,
            options.password ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16),SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
        return builder.build()
    }
    internal fun failure(e:Exception):HotspotFailure {
        val cause=(e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        if(cause is HotspotFailure) return cause
        return HotspotFailure(if(cause is SecurityException) "PERMISSION_DENIED" else "SYSTEM_ERROR",cause.message ?: cause.javaClass.simpleName)
    }
    private fun deviceState(context:Context):Map<String,Any?> = try {
        val manager=wifi(context)
        val value=manager.javaClass.getMethod("getWifiApState").invoke(manager) as Int
        mapOf("status" to when(value){10->"stopping";11->"stopped";12->"starting";13->"started";14->"failed";else->"unknown"},"rawState" to value)
    } catch(e:Exception) {mapOf("status" to "unknown","reason" to failure(e).message)}
    private fun stopAll(context:Context):Map<String,Any?> {
        val entries=synchronized(lock) {
            if(stopping) return mapOf("status" to "stopping","deviceStopped" to null)
            stopping=true;requests.values.toList()
        }
        try {
            entries.forEach {it.close()}
            val attempts=mutableListOf<Map<String,Any?>>()
            fun attempt(name:String,body:()->Any?) {
                try {val result=body();attempts.add(mapOf("method" to name,"status" to if(result==false) "rejected" else "requested"))}
                catch(e:Exception){val f=failure(e);attempts.add(mapOf("method" to name,"status" to if(f.code=="PERMISSION_DENIED") "permissionDenied" else "unavailable","code" to f.code,"message" to f.message))}
            }
            attempt("stopSoftAp") {val m=wifi(context);m.javaClass.getMethod("stopSoftAp").invoke(m)}
            if(Build.VERSION.SDK_INT>=36) attempt("stopWifiTethering") {InternetHotspot.stopDevice(context)}
            attempt("stopWifiTetheringByType") {val m=context.getSystemService(ConnectivityManager::class.java);m.javaClass.getMethod("stopTethering",Int::class.javaPrimitiveType).invoke(m,0)}
            val deadline=System.nanoTime()+2_000_000_000L
            var state=deviceState(context)
            while(state["status"] in listOf("started","starting","stopping") && System.nanoTime()<deadline) {Thread.sleep(100);state=deviceState(context)}
            val items=synchronized(lock){prune();requests.values.map {it.snapshot()}}
            return mapOf("status" to if(state["status"]=="stopped" && items.isEmpty()) "stopped" else if(state["status"]=="unknown") "unknown" else "incomplete",
                "deviceStopped" to when(state["status"]){"stopped"->true;"started","starting","stopping"->false;else->null},
                "device" to state,"requests" to items,"attempts" to attempts)
        } finally {synchronized(lock){stopping=false}}
    }
    fun hasActive():Boolean=synchronized(lock){requests.values.any {it.state=="started" && it.active}}
    /** Exact Wi-Fi/AP interfaces only; never bind wildcard, a VPN, or the station's uplink. */
    fun addresses(context:Context):List<Inet4Address> {
        if(!hasActive()) return emptyList()
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val excluded=cm.allNetworks.mapNotNull {cm.getLinkProperties(it)?.interfaceName}.toSet()
        return runCatching {NetworkInterface.getNetworkInterfaces().toList().filter {
            it.isUp && !it.isLoopback && it.name !in excluded && Regex("^(wlan|swlan|ap|softap|wifi)[0-9_].*").matches(it.name)
        }.flatMap {it.inetAddresses.toList().filterIsInstance<Inet4Address>().filter {a->a.isSiteLocalAddress && !a.isLinkLocalAddress}}}.getOrDefault(emptyList())
    }
}

/** Kept behind the SDK check so older phones and watches never load API 36 tethering classes. */
@Suppress("MissingPermission")
private object InternetHotspot {
    private val executor=java.util.concurrent.Executors.newCachedThreadPool {r->Thread(r,"andjs-hotspot").apply {isDaemon=true}}
    fun start(context:Context,request:HotspotRequest,config:SoftApConfiguration?) {
        val manager=context.getSystemService(android.net.TetheringManager::class.java)
            ?: throw HotspotFailure("UNSUPPORTED","系统不提供网络共享服务")
        val settings=android.net.TetheringManager.TetheringRequest.Builder(android.net.TetheringManager.TETHERING_WIFI).apply {if(config!=null) setSoftApConfiguration(config)}.build()
        val registered=java.util.concurrent.atomic.AtomicBoolean(false)
        val observer=object:android.net.TetheringManager.TetheringEventCallback {
            private var seen=false
            override fun onTetheredInterfacesChanged(interfaces:Set<android.net.TetheringInterface>) {
                val active=interfaces.any {it.type==android.net.TetheringManager.TETHERING_WIFI}
                if(active) seen=true
                if(seen && !active) {
                    request.platformStopped()
                    if(registered.getAndSet(false)) runCatching {manager.unregisterTetheringEventCallback(this)}
                }
            }
        }
        fun release() {
            stop(manager,settings)
            if(registered.getAndSet(false)) manager.unregisterTetheringEventCallback(observer)
        }
        request.setCancel {release()}
        manager.startTethering(settings,executor,object:android.net.TetheringManager.StartTetheringCallback {
            override fun onTetheringStarted() {
                try {registered.set(true);manager.registerTetheringEventCallback(executor,observer)} catch(_:Exception) {registered.set(false)}
                request.started(mapOf("name" to config?.ssid,"password" to config?.passphrase,"internetAvailable" to null),::release)
            }
            override fun onTetheringFailed(error:Int) {request.failed(HotspotFailure("TETHERING_FAILED_$error","系统拒绝网络共享（$error），请检查系统权限和运营商限制"))}
        })
    }
    private fun stop(manager:android.net.TetheringManager,request:android.net.TetheringManager.TetheringRequest) {
        val done=CountDownLatch(1);var failure:Int?=null
        manager.stopTethering(request,executor,object:android.net.TetheringManager.StopTetheringCallback {
            override fun onStopTetheringSucceeded(){done.countDown()}
            override fun onStopTetheringFailed(error:Int){failure=error;done.countDown()}
        })
        if(!done.await(3,TimeUnit.SECONDS)) throw HotspotFailure("STOP_TIMEOUT","系统未确认网络共享停止")
        failure?.let {throw HotspotFailure("STOP_FAILED_$it","系统未停止网络共享（$it）")}
    }
    fun stopDevice(context:Context) {stop(context.getSystemService(android.net.TetheringManager::class.java),android.net.TetheringManager.TetheringRequest.Builder(android.net.TetheringManager.TETHERING_WIFI).build())}
}
