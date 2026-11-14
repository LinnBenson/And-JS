package io.todu.andjs.widget

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.*
import io.todu.andjs.AndJsApplication
import io.todu.andjs.R
import io.todu.andjs.ui.*
import io.todu.andjs.ui.theme.AndJsTheme
import kotlinx.coroutines.*

/** Exists only while a user-requested desktop run is visible. Never restarted. */
class OverlayRunService:Service(),LifecycleOwner,SavedStateRegistryOwner,ViewModelStoreOwner {
    private val registry=LifecycleRegistry(this)
    override val lifecycle:Lifecycle get()=registry
    private val saved=SavedStateRegistryController.create(this)
    override val savedStateRegistry get()=saved.savedStateRegistry
    override val viewModelStore=ViewModelStore()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var view:ComposeView?=null
    private lateinit var model:HomeViewModel
    private val handler=Handler(Looper.getMainLooper())
    private val idleClose=Runnable {stopSelf()}
    private val screenOff=object:BroadcastReceiver() {override fun onReceive(c:Context,i:Intent) {stopSelf()}}
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate()
        saved.performAttach();saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val app=application as AndJsApplication
        model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(type:Class<T>):T = HomeViewModel(app.repository,app.runner,app.globals,app.categories,app.watchSync) as T
        })[HomeViewModel::class.java]
        registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("overlay-run","悬浮运行",NotificationManager.IMPORTANCE_LOW))
        val close=PendingIntent.getService(this,1,Intent(this,OverlayRunService::class.java).setAction("close"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(9100,Notification.Builder(this,"overlay-run").setSmallIcon(R.drawable.ic_code).setContentTitle("And JS · 悬浮运行").setContentText("关闭弹窗后结束运行").setOngoing(true).addAction(Notification.Action.Builder(null,"关闭",close).build()).build())
        if(intent?.action=="close") {stopSelf();return START_NOT_STICKY}
        val id=intent?.let {ScriptWidgetProvider.requestedId(this,it)}
        val app=application as AndJsApplication
        if(id==null || app.isWatch) {stopSelf();return START_NOT_STICKY}
        if(!Settings.canDrawOverlays(this)) {
            try {ScriptWidgetProvider.launchActivity(this,id).send()} catch(_:Exception) {}
            stopSelf();return START_NOT_STICKY
        }
        if(view!=null) return START_NOT_STICKY
        try {
            val displayContext=createDisplayContext(getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY))
            val windowContext=if(Build.VERSION.SDK_INT>=30) displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null) else displayContext
            val compose=ComposeView(android.view.ContextThemeWrapper(windowContext,R.style.Theme_AndJS))
            compose.setViewTreeLifecycleOwner(this)
            compose.setViewTreeSavedStateRegistryOwner(this)
            compose.setViewTreeViewModelStoreOwner(this)
            compose.setContent {
                val run by model.execution.collectAsState()
                val interaction by model.interaction.collectAsState()
                AndJsTheme {CompositionLocalProvider(LocalOverlayHost provides true) {
                    run?.let {RunScreen(it,model::stopRun,{stopSelf()},interaction)}
                }}
            }
            val params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
            params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            compose.setOnKeyListener {_,code,event->
                if(code==KeyEvent.KEYCODE_BACK && event.action==KeyEvent.ACTION_UP) {
                    model.interaction.value?.respond(null) ?: if(model.execution.value?.running==true) model.stopRun() else stopSelf()
                    true
                } else false
            }
            getSystemService(WindowManager::class.java).addView(compose,params)
            view=compose
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            scope.launch {
                try {
                    val item=app.repository.load().find {it.id==id && it.widget}
                    if(item==null) {stopSelf();return@launch}
                    model.run(item)
                    model.execution.collect {run->
                        handler.removeCallbacks(idleClose)
                        if(run!=null && !run.running) handler.postDelayed(idleClose,300_000)
                    }
                } catch(e:Exception) {if(e !is CancellationException) stopSelf()}
            }
        } catch(e:Exception) {
            android.util.Log.w("AndJS","Cannot open overlay",e)
            try {ScriptWidgetProvider.launchActivity(this,id).send()} catch(_:Exception) {}
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) {stopSelf()}
    override fun onTaskRemoved(rootIntent:Intent?) {stopSelf()}
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        model.background();scope.cancel()
        unregisterReceiver(screenOff)
        view?.let {it.disposeComposition();getSystemService(WindowManager::class.java).removeView(it)}
        view=null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
