package io.todu.andjs.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocationApi(private val context:Context) {
    private val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private fun granted(p:String)=context.checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED
    fun status()=mapOf("coarse" to granted(Manifest.permission.ACCESS_COARSE_LOCATION),"fine" to granted(Manifest.permission.ACCESS_FINE_LOCATION),"enabled" to (manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)))
    fun read(options:JSONObject,control:RunControl):Map<String,Any?>? {
        if(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) throw SecurityException("请先授予位置权限")
        val permission=status()
        fun number(key:String,default:Long,range:LongRange):Long {
            if(!options.has(key)) return default
            val v=options.get(key);require(v is Number && v.toDouble().isFinite() && v.toDouble()%1==0.0 && v.toDouble() in range.first.toDouble()..range.last.toDouble()) {"$key 超出范围"};return v.toLong()
        }
        val timeout=number("timeout",10000,1L..15000L)
        val maxAge=number("maxAge",60000,0L..86400000L)
        val start=SystemClock.elapsedRealtimeNanos()
        val providers=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).filter {it in manager.allProviders && manager.isProviderEnabled(it) && (it!=LocationManager.GPS_PROVIDER || permission["fine"]==true)}
        check(providers.isNotEmpty()) {"请开启定位服务；此设备可能需要精确位置权限"}
        fun result(l:Location,cached:Boolean)=mapOf("latitude" to l.latitude,"longitude" to l.longitude,"accuracy" to if(l.hasAccuracy()) l.accuracy else null,"altitude" to if(l.hasAltitude()) l.altitude else null,"speed" to if(l.hasSpeed()) l.speed else null,"bearing" to if(l.hasBearing()) l.bearing else null,"time" to l.time,"provider" to l.provider,"cached" to cached,"mock" to l.isFromMockProvider)
        if(maxAge>0) providers.mapNotNull {manager.getLastKnownLocation(it)}.filter {(start-it.elapsedRealtimeNanos)/1_000_000 in 0..maxAge}.maxByOrNull {it.elapsedRealtimeNanos}?.let {return result(it,true)}
        val done=CountDownLatch(1);val value=AtomicReference<Location?>(null)
        val listener=object:LocationListener {
            override fun onLocationChanged(location:Location) {
                if(location.elapsedRealtimeNanos<start || control.stopped.get()) return
                value.compareAndSet(null,Location(location));done.countDown()
            }
            override fun onProviderEnabled(provider:String) {}
            override fun onProviderDisabled(provider:String) {}
            @Deprecated("Platform callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
        }
        val release=control.registerStop {runCatching {manager.removeUpdates(listener)};done.countDown()}
        try {
            if(control.stopped.get()) return null
            providers.forEach {manager.requestLocationUpdates(it,1000,0f,listener,Looper.getMainLooper())}
            if(control.stopped.get()) return null
            done.await(timeout,TimeUnit.MILLISECONDS)
            return value.get()?.let {result(it,false)}
        } finally {runCatching {manager.removeUpdates(listener)};release()}
    }
}
