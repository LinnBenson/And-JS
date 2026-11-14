package io.todu.andjs.runtime

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.*
import android.provider.Settings
import org.json.JSONArray

class DeviceControls(private val context:Context) {
    private val watch=context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    @Suppress("DEPRECATION") private val vibrator:Vibrator
        get()=if(Build.VERSION.SDK_INT>=31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private fun write(key:String,value:Int):Boolean {
        check(Settings.System.canWrite(context)) {"请先授权修改系统设置"}
        check(Settings.System.putInt(context.contentResolver,key,value)) {"设备拒绝修改设置"}
        return true
    }
    fun call(namespace:String,method:String,args:JSONArray):Any? {
        fun integer(index:Int,default:Int?=null):Int {
            if(index>=args.length() && default!=null) return default
            val value=args.opt(index)
            require(value is Number && value.toDouble().isFinite() && value.toDouble()%1==0.0 && value.toDouble() in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {"参数 ${index+1} 必须为整数"}
            return value.toInt()
        }
        fun boolean(index:Int):Boolean {val value=args.opt(index);require(value is Boolean) {"参数 ${index+1} 必须为 boolean"};return value}
        fun setting(key:String)=Settings.System.getString(context.contentResolver,key)?.toIntOrNull()
        return when("$namespace.$method") {
            "device.memory"->{
                val info=ActivityManager.MemoryInfo();(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
                val runtime=Runtime.getRuntime()
                mapOf("totalBytes" to info.totalMem,"availableBytes" to info.availMem,"lowMemory" to info.lowMemory,"thresholdBytes" to info.threshold,"appHeapUsedBytes" to (runtime.totalMemory()-runtime.freeMemory()),"appHeapMaxBytes" to runtime.maxMemory())
            }
            "device.storage"->{val stat=StatFs(context.filesDir.absolutePath);mapOf("totalBytes" to stat.totalBytes,"availableBytes" to stat.availableBytes,"freeBytes" to stat.freeBytes)}
            "device.screen"->{val metrics=context.resources.displayMetrics;val config=context.resources.configuration
                mapOf("widthPixels" to metrics.widthPixels,"heightPixels" to metrics.heightPixels,"density" to metrics.density,"densityDpi" to metrics.densityDpi,"fontScale" to config.fontScale,"round" to config.isScreenRound,"orientation" to when(config.orientation) {android.content.res.Configuration.ORIENTATION_PORTRAIT->"portrait";android.content.res.Configuration.ORIENTATION_LANDSCAPE->"landscape";else->"undefined"})}
            "device.power"->{val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager;val lock=context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                mapOf("interactive" to power.isInteractive,"powerSave" to power.isPowerSaveMode,"idle" to power.isDeviceIdleMode,"locked" to lock.isKeyguardLocked,"uptimeMs" to SystemClock.uptimeMillis(),"elapsedRealtimeMs" to SystemClock.elapsedRealtime())}
            "device.sensors"->(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL).map {mapOf("name" to it.name,"vendor" to it.vendor,"type" to it.type,"stringType" to it.stringType,"powerMa" to it.power,"maxRange" to it.maximumRange,"resolution" to it.resolution,"minDelayUs" to it.minDelay,"wakeUp" to it.isWakeUpSensor)}
            "device.vibrator"->mapOf("available" to vibrator.hasVibrator(),"amplitudeControl" to vibrator.hasAmplitudeControl())
            "system.setAutoBrightness"->write(Settings.System.SCREEN_BRIGHTNESS_MODE,if(boolean(0)) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            "system.getScreenTimeout"->setting(Settings.System.SCREEN_OFF_TIMEOUT)
            "system.setScreenTimeout"->{val value=integer(0);require(value in 5000..1800000) {"息屏时间应为 5000–1800000 毫秒"};write(Settings.System.SCREEN_OFF_TIMEOUT,value)}
            "system.getRotation"->{require(!watch) {"屏幕旋转接口仅适用于手机"};mapOf("automatic" to setting(Settings.System.ACCELEROMETER_ROTATION)?.let {it!=0},"rotation" to setting(Settings.System.USER_ROTATION))}
            "system.setRotation"->{require(!watch) {"屏幕旋转接口仅适用于手机"};val automatic=boolean(0);val rotation=integer(1,0);require(rotation in 0..3) {"rotation 必须为 0–3"};write(Settings.System.USER_ROTATION,rotation);write(Settings.System.ACCELEROMETER_ROTATION,if(automatic) 1 else 0)}
            "system.vibrate"->{val duration=integer(0,100);val amplitude=integer(1,-1);require(duration in 1..(if(watch) 1000 else 5000)) {"振动时长超出限制"};require(amplitude==-1 || amplitude in 1..255) {"振动强度应为 -1 或 1–255"};check(vibrator.hasVibrator()) {"此设备不支持振动"};vibrator.vibrate(VibrationEffect.createOneShot(duration.toLong(),amplitude));true}
            "system.cancelVibration"->{vibrator.cancel();true}
            else->error("未知设备接口")
        }
    }
}
