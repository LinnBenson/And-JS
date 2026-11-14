package io.todu.andjs.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WatchHealthApi(private val context:Context) {
    fun supported(control:RunControl):Boolean {
        if(Build.VERSION.SDK_INT<30) return false
        val future=HealthServices.getClient(context).passiveMonitoringClient.getCapabilitiesAsync()
        val release=control.registerStop {future.cancel(true)}
        return try {DataType.STEPS_DAILY in future.get(3,TimeUnit.SECONDS).supportedDataTypesPassiveMonitoring}
        finally {future.cancel(true);release()}
    }
    fun steps(control:RunControl):Map<String,Any?> {
        if(Build.VERSION.SDK_INT<30) error("今日步数需要 Wear OS 3 或更新版本")
        check(supported(control)) {"此手表不支持 Health Services 今日步数"}
        check(context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED) {"请先授予活动识别权限"}
        val client=HealthServices.getClient(context).passiveMonitoringClient
        val done=CountDownLatch(1);val closed=AtomicBoolean(false)
        val value=AtomicReference<IntervalDataPoint<Long>?>(null);val failure=AtomicReference<Throwable?>(null)
        val start=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun clear() {closed.set(true);runCatching {client.clearPassiveListenerCallbackAsync()};done.countDown()}
        val callback=object:PassiveListenerCallback {
            override fun onRegistered() {if(!closed.get()) client.flushAsync()}
            override fun onRegistrationFailed(throwable:Throwable) {failure.set(throwable);done.countDown()}
            override fun onPermissionLost() {failure.set(SecurityException("活动识别权限已撤销"));done.countDown()}
            override fun onNewDataPointsReceived(dataPoints:DataPointContainer) {
                if(closed.get()) return
                val point=dataPoints.getData(DataType.STEPS_DAILY).maxByOrNull {it.endDurationFromBoot} ?: return
                val end=System.currentTimeMillis()-SystemClock.elapsedRealtime()+point.endDurationFromBoot.toMillis()
                if(end<start) return
                value.set(point);done.countDown()
            }
        }
        val release=control.registerStop {clear()}
        try {
            if(!control.stopped.get()) {
                client.setPassiveListenerCallback(PassiveListenerConfig.builder().setDataTypes(setOf(DataType.STEPS_DAILY)).build(),callback)
                if(!control.stopped.get()) done.await(10,TimeUnit.SECONDS)
            }
            failure.get()?.let {throw IllegalStateException("读取今日步数失败：${it.message}",it)}
            val point=value.get()
            val boot=System.currentTimeMillis()-SystemClock.elapsedRealtime()
            return mapOf("type" to "steps","start" to (point?.startDurationFromBoot?.toMillis()?.plus(boot) ?: start),"end" to (point?.endDurationFromBoot?.toMillis()?.plus(boot) ?: System.currentTimeMillis()),"value" to point?.value,"unit" to "count","source" to "healthServices")
        } finally {clear();release()}
    }
}
