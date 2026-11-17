package io.todu.andjs.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

class AndroidPlatformApi(private val context:Context):PlatformApi {
    override fun openHotspot(options:HotspotOptions,control:RunControl):HotspotSession = AndroidHotspots.start(context.applicationContext,options,control)
    override fun hotspot(method:String):Map<String,Any?> = AndroidHotspots.call(context.applicationContext,method)
    override fun openHttpServer(port:Int,token:String,maxFileSizeMB:Int,control:RunControl,name:String):HttpServerSession = AndroidHttpServer.open(context.applicationContext,HttpServerOptions(port,token,maxFileSizeMB,name=name),control)
    override fun openHttpChat(port:Int,token:String,maxFileSizeMB:Int,maxImageSizeMB:Int,control:RunControl,name:String):HttpServerSession = AndroidHttpServer.open(context.applicationContext,HttpServerOptions(port,token,maxFileSizeMB,true,maxImageSizeMB,name),control)
    override fun openDeviceSession(control:RunControl):DeviceSession=AndroidDeviceSession(context.applicationContext,control)
    override fun openBle(control:RunControl):BleSession=AndroidBleSession(context.applicationContext,control)
    override fun call(namespace:String,method:String,args:JSONArray,control:RunControl):Any? =
        when {
            namespace=="system" && method in listOf("speak","playAudio")->ScriptAudio.play(context,method,args,control) {path->
                ScriptFiles(File(context.filesDir,"user"),Environment.getExternalStorageDirectory(),lowMemory=watch) {permissions()["sharedFiles"]==true}.resolve(path)
            }
            namespace=="health"->HealthApi(context).call(method,args,control)
            namespace=="device" && method=="location"->LocationApi(context).read(if(args.length()>0) args.getJSONObject(0) else org.json.JSONObject(),control)
            namespace=="device" && method in listOf("wifiList","bluetoothList")->RadioListApi(context).let { api->
                val options=if(args.length()>0) args.getJSONObject(0) else org.json.JSONObject()
                if(method=="wifiList") api.wifiList(options,control) else api.bluetoothList(options,control)
            }
            else->call(namespace,method,args)
        }
    private val watch=context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    private val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    fun permissions():Map<String,Any> = mapOf(
        "internet" to true,
        "accessibility" to SystemActionsService.available(),
        "overlay" to (!watch && Settings.canDrawOverlays(context)),
        "notifications" to context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled(),
        "watch" to watch,
        "writeSettings" to Settings.System.canWrite(context),
        "sharedFiles" to (!watch && if(Build.VERSION.SDK_INT>=30) Environment.isExternalStorageManager() else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED),
    )
    private fun stream(name:String):Int = when(name) {"music"->AudioManager.STREAM_MUSIC;"alarm"->AudioManager.STREAM_ALARM;"ring"->AudioManager.STREAM_RING;"notification"->AudioManager.STREAM_NOTIFICATION;else->throw IllegalArgumentException("未知音量类型")}
    override fun call(namespace:String,method:String,args:JSONArray):Any? {
        fun string(index:Int,default:String?=null):String {
            if(index>=args.length() && default!=null) return default
            val value=args.get(index);require(value is String) {"参数 ${index+1} 必须为字符串"};return value
        }
        fun number(index:Int):Int {val v=args.get(index);require(v is Number && v.toDouble().isFinite() && v.toDouble()%1==0.0 && v.toDouble() in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {"参数必须为整数"};return v.toInt()}
        return when(namespace) {
            "notes"->{
                val app=context.applicationContext as io.todu.andjs.AndJsApplication
                ScriptNotes({if(watch) app.watchStore.state.value.notes else app.watchSync.notes()}, {index,text->
                    if(watch) app.watchStore.writeNote(index,text) else app.watchSync.saveNoteNow(index,text)
                }).call(method,args)
            }
            "cache"->ScriptCache(File(context.filesDir,"script-cache")).call(method,args)
            "clipboard"->ScriptExtras.clipboard(context,method,args)
            "util"->if(method=="qr") ScriptExtras.qr(string(0)) else error("未知方法")
            "image"->LogImages.prepare(string(0),watch)
            "health"->HealthApi(context).call(method,args)
            "permissions"->permissions()
            "device"->when(method) {
                "memory","storage","screen","power","sensors","vibrator"->DeviceControls(context).call(namespace,method,args)
                "info"->mapOf("watch" to watch,"manufacturer" to Build.MANUFACTURER,"brand" to Build.BRAND,"model" to Build.MODEL,"device" to Build.DEVICE,"androidVersion" to Build.VERSION.RELEASE,"sdk" to Build.VERSION.SDK_INT,"abis" to Build.SUPPORTED_ABIS.toList(),"language" to java.util.Locale.getDefault().toLanguageTag(),"timeZone" to java.util.TimeZone.getDefault().id)
                "battery"->{
                    val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
                    val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
                    val status=battery?.getIntExtra(BatteryManager.EXTRA_STATUS,-1) ?: -1
                    mapOf("percent" to if(level>=0 && scale>0) level*100.0/scale else null,"charging" to (status==BatteryManager.BATTERY_STATUS_CHARGING || status==BatteryManager.BATTERY_STATUS_FULL),"temperature" to if(battery?.hasExtra(BatteryManager.EXTRA_TEMPERATURE)==true) battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0 else null,"plugged" to ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0)!=0))
                }
                "network"->ConnectivityApi(context).network()
                "wifi"->ConnectivityApi(context).wifi()
                "bluetooth"->ConnectivityApi(context).bluetooth()
                "locationStatus"->LocationApi(context).status()
                else->error("未知方法")
            }
            "system"->when(method) {
                "openUrl"->ScriptExtras.openUrl(context,string(0))
                "openApp"->ScriptExtras.openApp(context,string(0))
                "share"->ScriptExtras.share(context,string(0)) {path->ScriptFiles(File(context.filesDir,"user"),Environment.getExternalStorageDirectory(),lowMemory=watch) {permissions()["sharedFiles"]==true}.resolve(path)}
                "openRecents"->SystemActionsService.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                "openNotificationShade"->SystemActionsService.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "toast"->{
                    val text=string(0);require(text.isNotBlank() && text.length<=2000) {"提示内容须为 1–2000 字符"}
                    val duration=string(1,"short");require(duration in listOf("short","long")) {"时长须为 short 或 long"}
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context.applicationContext,text,if(duration=="long") android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                "closeApp"->{
                    val app=context.applicationContext
                    val handler=android.os.Handler(android.os.Looper.getMainLooper())
                    check(handler.post {
                        // Terminate even if a window or task fails to close cleanly.
                        handler.postDelayed({android.os.Process.killProcess(android.os.Process.myPid())},100L)
                        try {app.stopService(Intent(app,io.todu.andjs.widget.OverlayRunService::class.java))} catch(_:Exception) {}
                        val manager=app.getSystemService(android.app.ActivityManager::class.java)
                        try {manager?.appTasks?.forEach {task->try {task.finishAndRemoveTask()} catch(_:Exception) {}}} catch(_:Exception) {}
                    }) {"无法关闭 And JS"}
                    true
                }
                "returnToWatchFace"->{
                    require(watch) {"仅手表支持返回表盘"}
                    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
                "notify","cancelNotification"->ScriptNotifications.call(context,method,args)
                "setAutoBrightness","getScreenTimeout","setScreenTimeout","getRotation","setRotation","vibrate","cancelVibration"->DeviceControls(context).call(namespace,method,args)
                "getBrightness"->mapOf("value" to Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,128),"automatic" to (Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,0)==Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC))
                "setBrightness"->{
                    check(Settings.System.canWrite(context)) {"请先授权修改系统设置"};val value=number(0);require(value in 0..255)
                    check(Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
                    check(Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,value));true
                }
                "getVolume"->{val s=stream(string(0,"music"));mapOf("value" to audio.getStreamVolume(s),"max" to audio.getStreamMaxVolume(s))}
                "setVolume"->{val value=number(0);val s=stream(string(1,"music"));require(value in 0..audio.getStreamMaxVolume(s));audio.setStreamVolume(s,value,0);true}
                "openSettings"->{
                    val page=string(0,"app")
                    val action=when(page) {"app"->Settings.ACTION_APPLICATION_DETAILS_SETTINGS;"wifi"->Settings.ACTION_WIFI_SETTINGS;"hotspot"->"android.settings.TETHER_SETTINGS";"bluetooth"->Settings.ACTION_BLUETOOTH_SETTINGS;"display"->Settings.ACTION_DISPLAY_SETTINGS;"sound"->Settings.ACTION_SOUND_SETTINGS;"battery"->Settings.ACTION_BATTERY_SAVER_SETTINGS;"location"->Settings.ACTION_LOCATION_SOURCE_SETTINGS;"date"->Settings.ACTION_DATE_SETTINGS;"writeSettings"->Settings.ACTION_MANAGE_WRITE_SETTINGS;else->throw IllegalArgumentException("不支持的设置页")}
                    val intent=Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if(action==Settings.ACTION_APPLICATION_DETAILS_SETTINGS || action==Settings.ACTION_MANAGE_WRITE_SETTINGS) intent.data=Uri.parse("package:${context.packageName}")
                    (context.applicationContext as? io.todu.andjs.AndJsApplication)?.externalFlow=true
                    try {context.startActivity(intent)} catch(e:Exception) {
                        (context.applicationContext as? io.todu.andjs.AndJsApplication)?.externalFlow=false
                        if(page=="hotspot") when(e) {
                            is android.content.ActivityNotFoundException->throw IllegalStateException("系统未提供可打开的热点设置页面",e)
                            is SecurityException->throw IllegalStateException("系统禁止打开热点设置页面",e)
                        }
                        throw e
                    };true
                }
                else->error("未知方法")
            }
            "files"->ScriptFiles(File(context.filesDir,"user"),Environment.getExternalStorageDirectory(),lowMemory=watch) {permissions()["sharedFiles"]==true}.call(method,args)
            else->error("未知方法")
        }
    }
}
