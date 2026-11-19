package io.todu.andjs
import android.os.Bundle
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.todu.andjs.ui.*
import io.todu.andjs.ui.theme.AndJsTheme
class MainActivity : ComponentActivity() {
    private val complicationRequest=androidx.compose.runtime.mutableStateOf<String?>(null)
    private val widgetRequest=androidx.compose.runtime.mutableStateOf<String?>(null)
    override fun onNewIntent(intent:android.content.Intent) {super.onNewIntent(intent);setIntent(intent);widgetRequest.value=io.todu.andjs.widget.ScriptWidgetProvider.requestedId(this,intent);complicationRequest.value=io.todu.andjs.widget.WatchComplicationSlots.requestedScript(this,intent)}
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private var stopWork:(()->Unit)?=null
    private val exitTask=Runnable {if(!isFinishing && !(application as AndJsApplication).voiceInputActive) {stopWork?.invoke();finishAndRemoveTask()}}
    override fun startActivityForResult(intent:android.content.Intent,requestCode:Int,options:Bundle?) {
        (application as AndJsApplication).externalFlow=true
        handler.removeCallbacks(exitTask)
        try {super.startActivityForResult(intent,requestCode,options)} catch(e:Exception) {(application as AndJsApplication).externalFlow=false;throw e}
    }
    override fun startActivity(intent:android.content.Intent,options:Bundle?) {
        val app=application as AndJsApplication
        app.externalFlow=true
        handler.removeCallbacks(exitTask)
        try {super.startActivity(intent,options)} catch(e:Exception) {app.externalFlow=false;throw e}
    }
    override fun onStart() {
        super.onStart();handler.removeCallbacks(exitTask)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {io.todu.andjs.runtime.HttpUploadFile.clean(this@MainActivity)}
            try {io.todu.andjs.runtime.ScriptCache(java.io.File(filesDir,"script-cache")).clean();io.todu.andjs.runtime.ScriptExtras.cleanShares(this@MainActivity)} catch(_:Exception) {}
        }
    }
    override fun onResume() {super.onResume();(application as AndJsApplication).externalFlow=false}
    override fun onStop() {
        super.onStop()
        if(!isChangingConfigurations) {
            val app=application as AndJsApplication
            if(!app.voiceInputActive) stopWork?.invoke()
            io.todu.andjs.runtime.DeviceControls(this).call("system","cancelVibration",org.json.JSONArray())
            if(!app.externalFlow && !app.voiceInputActive) handler.postDelayed(exitTask,if(app.isWatch) 0 else 15_000)
        }
    }
    override fun onTrimMemory(level:Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN reports visibility, not memory pressure (e.g. system voice input).
        val memoryPressure = level in android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if(memoryPressure) stopWork?.invoke()
    }
    override fun onDestroy() {handler.removeCallbacks(exitTask);super.onDestroy()}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetRequest.value=if(savedInstanceState==null) io.todu.andjs.widget.ScriptWidgetProvider.requestedId(this,intent) else null
        complicationRequest.value=if(savedInstanceState==null) io.todu.andjs.widget.WatchComplicationSlots.requestedScript(this,intent) else null
        enableEdgeToEdge()
        if((application as AndJsApplication).isWatch) lifecycleScope.launch {try {io.todu.andjs.wear.recoverWatch(this@MainActivity)} catch(_:Exception) {}}

        setContent { AndJsTheme {
            val lightBars=androidx.compose.material3.MaterialTheme.colorScheme.background.luminance()>.5f
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars=lightBars
                    isAppearanceLightNavigationBars=lightBars
                }
            }
            val app = application as AndJsApplication
            if(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)) {
                val watch:io.todu.andjs.wear.WatchViewModel=viewModel(factory=object:ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=io.todu.andjs.wear.WatchViewModel(app.watchStore,app.runner) as T
                })
                androidx.compose.runtime.LaunchedEffect(widgetRequest.value,complicationRequest.value) {
                    val shortcut=complicationRequest.value
                    val id=shortcut ?: widgetRequest.value
                    if(id!=null) {widgetRequest.value=null;complicationRequest.value=null;app.watchStore.state.value.scripts.find {it.item.id==id && (it.item.widget || shortcut==id)}?.let {window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);watch.run(it,fromWatchFace=true)}}
                }
                stopWork=watch::background
                WatchScreen(watch) {
                    watch.close()
                    handler.removeCallbacks(exitTask)
                    finishAndRemoveTask()
                }
            } else {
            val model: HomeViewModel = viewModel(factory = object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app.repository, app.runner, app.globals, app.categories, app.watchSync, onRunStarted={window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)}) as T
            })
            androidx.compose.runtime.LaunchedEffect(widgetRequest.value) {
                val id=widgetRequest.value
                if(id!=null) {try {val items=app.repository.load();items.find {it.id==id && it.widget}?.let {model.run(it)}} finally {widgetRequest.value=null}}
            }
            stopWork=model::background
            HomeScreen(model)
            }
        } }
    }
}
