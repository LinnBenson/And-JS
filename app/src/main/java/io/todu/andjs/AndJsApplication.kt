package io.todu.andjs
import android.app.Application
import io.todu.andjs.data.FileScriptRepository
import io.todu.andjs.runtime.RhinoScriptRunner
class AndJsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {io.todu.andjs.runtime.HttpChatStore.recover(java.io.File(noBackupFilesDir,"http-chat"))}
        registerActivityLifecycleCallbacks(io.todu.andjs.runtime.ForegroundActivity)
        runCatching {io.todu.andjs.runtime.ScriptDnd.recover(this)}
    }
    val isWatch by lazy {packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)}
    var externalFlow=false
    var voiceInputActive=false
    val watchStore by lazy { io.todu.andjs.wear.WatchStore(filesDir) }
    val watchSync by lazy { io.todu.andjs.wear.WatchSync(this) }
    val repository by lazy { FileScriptRepository(this) }
    val categories by lazy { io.todu.andjs.data.CategoryRepository(this) }
    val globals by lazy { io.todu.andjs.data.GlobalRepository(this) }
    val runner by lazy { RhinoScriptRunner(lowMemory=isWatch,networkRoot=java.io.File(filesDir,"user/network"),platform=io.todu.andjs.runtime.AndroidPlatformApi(this)) }
}
