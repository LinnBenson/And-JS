package io.todu.andjs.runtime

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded, foreground-only snapshots. Android callbacks never execute JavaScript. */
@Suppress("DEPRECATION", "MissingPermission")
class RadioListApi(private val context:Context) {
    companion object {
        private val wifiBusy=AtomicBoolean(false)
        private val bluetoothBusy=AtomicBoolean(false)
    }
    private val watch=context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    private fun integer(o:JSONObject,key:String,default:Int,max:Int):Int {
        if(!o.has(key)) return default
        val n=o.get(key) as? Number ?: throw IllegalArgumentException("$key 须为整数")
        require(n.toDouble().isFinite() && n.toDouble()%1==0.0 && n.toDouble() in 1.0..max.toDouble()) {"$key 应为 1–$max 的整数"}
        return n.toInt()
    }
    private fun permission(name:String) {
        if(context.checkSelfPermission(name)!=PackageManager.PERMISSION_GRANTED) throw SecurityException("请在设置的权限授权中允许"+if(name==Manifest.permission.ACCESS_FINE_LOCATION) "精确位置" else "蓝牙访问")
    }
    private fun location() {
        permission(Manifest.permission.ACCESS_FINE_LOCATION)
        check(LocationApi(context).status()["enabled"]==true) {"请开启系统定位服务"}
    }
    private fun cancelled(control:RunControl) {
        if(control.stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException("扫描已停止")
    }
    fun wifiList(o:JSONObject,control:RunControl):Map<String,Any?> {
        val started=SystemClock.elapsedRealtime()
        val timeout=integer(o,"timeout",10000,15000)
        val limit=integer(o,"maxItems",if(watch) 40 else 100,if(watch) 40 else 100)
        require(!o.has("refresh") || o.get("refresh") is Boolean) {"refresh 须为 boolean"}
        val refresh=o.optBoolean("refresh",true)
        check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {"此设备不支持 Wi-Fi"}
        val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: error("Wi-Fi 服务不可用")
        location();cancelled(control)
        check(wifi.isWifiEnabled || wifi.isScanAlwaysAvailable) {"请开启 Wi-Fi 或系统 Wi-Fi 扫描"}
        check(wifiBusy.compareAndSet(false,true)) {"已有 Wi-Fi 列表请求正在进行"}
        val done=CountDownLatch(1)
        val updated=AtomicReference<Boolean?>(null)
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context,intent:Intent) {
                if(intent.action==WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    updated.compareAndSet(null,intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED,false));done.countDown()
                }
            }
        }
        var registered=false
        val release=control.registerStop {done.countDown()}
        try {
            cancelled(control)
            var status="cached"
            if(refresh) {
                ContextCompat.registerReceiver(context,receiver,IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),ContextCompat.RECEIVER_NOT_EXPORTED)
                registered=true
                cancelled(control)
                status=if(!wifi.startScan()) "rejected" else {
                    done.await(timeout.toLong(),TimeUnit.MILLISECONDS)
                    when(updated.get()) {true->"updated";false->"failed";null->"timeout"}
                }
            }
            cancelled(control)
            val now=SystemClock.elapsedRealtimeNanos()/1000
            val all=wifi.scanResults.distinctBy {it.BSSID to it.frequency}.sortedByDescending {it.level}
            val items=all.take(limit).map {r->mapOf(
                "ssid" to r.SSID.orEmpty(),"bssid" to r.BSSID,"rssi" to r.level,
                "frequencyMhz" to r.frequency,"capabilities" to r.capabilities,
                "ageMs" to if(r.timestamp>0 && now>=r.timestamp) (now-r.timestamp)/1000 else null
            )}
            return mapOf("items" to items,"status" to status,"refreshed" to (status=="updated"),"truncated" to (all.size>limit),"elapsedMs" to (SystemClock.elapsedRealtime()-started))
        } finally {
            if(registered) runCatching {context.unregisterReceiver(receiver)}
            release();wifiBusy.set(false)
        }
    }
    fun bluetoothList(o:JSONObject,control:RunControl):Map<String,Any?> {
        val started=SystemClock.elapsedRealtime()
        require(!o.has("mode") || o.get("mode") is String) {"mode 须为字符串"}
        val mode=o.optString("mode","paired")
        require(mode in listOf("paired","classic","ble")) {"mode 须为 paired、classic 或 ble"}
        val timeout=integer(o,"timeout",10000,15000)
        val limit=integer(o,"maxItems",if(watch) 40 else 100,if(watch) 40 else 100)
        val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: error("此设备不支持蓝牙")
        if(Build.VERSION.SDK_INT>=31) permission(Manifest.permission.BLUETOOTH_CONNECT)
        cancelled(control)
        check(adapter.isEnabled) {"请开启蓝牙"}
        fun device(d:BluetoothDevice,rssi:Int?=null,name:String?=null)=mapOf<String,Any?>(
            "name" to (name ?: d.name),"address" to d.address,"type" to when(d.type) {BluetoothDevice.DEVICE_TYPE_CLASSIC->"classic";BluetoothDevice.DEVICE_TYPE_LE->"ble";BluetoothDevice.DEVICE_TYPE_DUAL->"dual";else->"unknown"},
            "bonded" to (d.bondState==BluetoothDevice.BOND_BONDED),"rssi" to rssi
        )
        if(mode=="paired") {
            val all=adapter.bondedDevices.sortedBy {it.address}
            return mapOf("items" to all.take(limit).map {device(it)},"mode" to mode,"completed" to true,"timedOut" to false,"truncated" to (all.size>limit),"elapsedMs" to (SystemClock.elapsedRealtime()-started))
        }
        if(Build.VERSION.SDK_INT>=31) permission(Manifest.permission.BLUETOOTH_SCAN)
        location()
        check(bluetoothBusy.compareAndSet(false,true)) {"已有蓝牙扫描正在进行"}
        val done=CountDownLatch(1)
        val error=AtomicReference<Exception?>(null)
        val active=AtomicBoolean(true)
        val items=linkedMapOf<String,Map<String,Any?>>()
        var truncated=false
        val completed=AtomicBoolean(false)
        var registered=false
        var scanning=false
        var scanner:android.bluetooth.le.BluetoothLeScanner?=null
        fun add(d:BluetoothDevice,rssi:Int?,name:String?=null) {
            if(!active.get() || control.stopped.get()) return
            try {
                val item=device(d,rssi,name)
                synchronized(items) {
                    if(items.containsKey(d.address) || items.size<limit) items[d.address]=item else truncated=true
                }
            } catch(e:Exception) {error.compareAndSet(null,e);done.countDown()}
        }
        val callback=object:ScanCallback() {
            override fun onScanResult(callbackType:Int,result:ScanResult) {add(result.device,result.rssi,result.scanRecord?.deviceName)}
            override fun onBatchScanResults(results:MutableList<ScanResult>) {results.forEach {onScanResult(0,it)}}
            override fun onScanFailed(errorCode:Int) {error.compareAndSet(null,IllegalStateException("BLE 扫描失败：$errorCode"));done.countDown()}
        }
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context,intent:Intent) {
                if(!active.get()) return
                when(intent.action) {
                    BluetoothDevice.ACTION_FOUND->{
                        val d=intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        add(d,if(intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) intent.getShortExtra(BluetoothDevice.EXTRA_RSSI,0).toInt() else null,intent.getStringExtra(BluetoothDevice.EXTRA_NAME))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED->{completed.set(true);done.countDown()}
                    BluetoothAdapter.ACTION_STATE_CHANGED->if(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1)!=BluetoothAdapter.STATE_ON) {error.compareAndSet(null,IllegalStateException("蓝牙已关闭"));done.countDown()}
                }
            }
        }
        // Stop wakes this worker; its finally block cancels this scan and removes receivers.
        val release=control.registerStop {active.set(false);done.countDown()}
        try {
            cancelled(control)
            val filter=IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                if(mode=="classic") {addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)}
            }
            ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_EXPORTED)
            registered=true
            cancelled(control)
            if(mode=="ble") scanner=adapter.bluetoothLeScanner
            if(mode=="classic") {
                check(!adapter.isDiscovering) {"系统正在扫描蓝牙，请稍后重试"}
                check(adapter.startDiscovery()) {"无法启动蓝牙扫描，请稍后重试"}
                scanning=true
            } else {
                check(scanner!=null) {"此设备无法扫描 BLE"}
                scanning=true
                scanner.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),callback)
            }
            cancelled(control)
            done.await(timeout.toLong(),TimeUnit.MILLISECONDS)
            cancelled(control)
            error.get()?.let {throw it}
            active.set(false)
            return synchronized(items) {
                mapOf("items" to items.values.sortedByDescending {(it["rssi"] as? Int) ?: -127},"mode" to mode,
                    "completed" to (mode=="ble" || completed.get()),"timedOut" to (mode=="classic" && !completed.get()),"truncated" to truncated,"elapsedMs" to (SystemClock.elapsedRealtime()-started))
            }
        } finally {
            active.set(false)
            if(scanning) runCatching {if(mode=="classic") adapter.cancelDiscovery() else scanner?.stopScan(callback)}
            if(registered) runCatching {context.unregisterReceiver(receiver)}
            release();bluetoothBusy.set(false)
        }
    }
}
