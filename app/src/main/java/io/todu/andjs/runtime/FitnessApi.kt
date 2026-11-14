package io.todu.andjs.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FitnessApi(private val context:Context) {
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private fun granted(permission:String)=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    fun permissions():Array<String> = buildList {
        if(manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)!=null) add(Manifest.permission.BODY_SENSORS)
        if(Build.VERSION.SDK_INT>=29 && (manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)!=null || context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }.toTypedArray()
    fun status():Map<String,Boolean> = mapOf(
        "steps" to (manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)!=null),
        "heartRate" to (manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)!=null),
        "stepsGranted" to (Build.VERSION.SDK_INT<29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)),
        "heartRateGranted" to granted(Manifest.permission.BODY_SENSORS))
    fun call(method:String,control:RunControl?=null):Any? {
        if(method=="status") return status()
        val type=when(method) {"steps"->Sensor.TYPE_STEP_COUNTER;"heartRate"->Sensor.TYPE_HEART_RATE;else->error("未知健身方法")}
        val sensor=manager.getDefaultSensor(type) ?: error("此设备不支持${if(method=="steps") "步数" else "心率"}传感器")
        check(status()[if(method=="steps") "stepsGranted" else "heartRateGranted"]==true) {"请先授予健身传感器权限"}
        val done=CountDownLatch(1)
        val result=AtomicReference<Map<String,Any>?>(null)
        val closed=AtomicBoolean(false)
        val listener=object:SensorEventListener {
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
            override fun onSensorChanged(event:SensorEvent) {
                if(closed.get() || event.values.isEmpty()) return
                val value=event.values[0].toDouble()
                if(!value.isFinite() || value<0 || (method=="heartRate" && (value<=0 || event.accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE))) return
                result.compareAndSet(null,mapOf((if(method=="steps") "count" else "bpm") to value,"time" to (System.currentTimeMillis()-(SystemClock.elapsedRealtimeNanos()-event.timestamp)/1_000_000),"accuracy" to event.accuracy))
                done.countDown()
            }
        }
        val release=control?.registerStop {closed.set(true);manager.unregisterListener(listener);done.countDown()}
        try {
            if(closed.get()) return null
            check(manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_NORMAL,Handler(Looper.getMainLooper()))) {"无法启动健身传感器"}
            if(closed.get()) return null
            done.await(10,TimeUnit.SECONDS)
            return result.get()
        } finally {closed.set(true);manager.unregisterListener(listener);release?.invoke()}
    }
}
