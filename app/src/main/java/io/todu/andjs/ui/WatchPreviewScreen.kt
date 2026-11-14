package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.todu.andjs.runtime.*
import io.todu.andjs.wear.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File

@Composable fun WatchPreviewScreen(phone:HomeViewModel,onBack:()->Unit) {
    val context=LocalContext.current.applicationContext
    val owner=LocalLifecycleOwner.current
    val models=remember {ViewModelStore()}
    var model by remember {mutableStateOf<WatchViewModel?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) model?.background()}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);models.clear()}
    }
    LaunchedEffect(revision) {
        models.clear();model=null;error=null
        try {
            val snapshot=phone.previewSnapshot()
            val store=withContext(Dispatchers.IO) {
                WatchStore(File(context.filesDir,"watch-preview").apply {mkdirs()}).also {
                    val text=snapshot.encode();it.apply(text,"preview",it.sequence+1,WatchSnapshot.hash(text))
                }
            }
            val root=File(context.filesDir,"watch-preview/user")
            val platform=object:PlatformApi {
                private val host=AndroidPlatformApi(context)
                override fun call(namespace:String,method:String,args:org.json.JSONArray):Any? = when(namespace) {
                    "image"->LogImages.prepare(args.getString(0),true)
                    "files"->ScriptFiles(root,root,lowMemory=true) {false}.call(method,args)
                    "health"->if(method=="status") mapOf("sensors" to mapOf("steps" to false,"heartRate" to false,"stepsGranted" to false,"heartRateGranted" to false),"dailySteps" to false,"history" to mapOf("available" to false,"status" to "unsupported","granted" to emptyList<String>()),"source" to "preview") else error("请在真实设备上读取健康数据")
                    "permissions"->mapOf("internet" to true,"watch" to true,"writeSettings" to false,"sharedFiles" to false)
                    "system"->if(method.startsWith("set") || method in listOf("openSettings","vibrate","cancelVibration")) error("预览不修改手机系统设置") else host.call(namespace,method,args)
                    "device"->if(method=="location") error("请在真实设备上读取位置") else if(method=="info") (host.call(namespace,method,args) as Map<*,*>).entries.associate {it.key.toString() to it.value}+mapOf("watch" to true) else host.call(namespace,method,args)
                    else->host.call(namespace,method,args)
                }
            }
            model=ViewModelProvider(models,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = WatchViewModel(store,RhinoScriptRunner(lowMemory=true,networkRoot=File(root,"network"),platform=platform)) as T
            })[WatchViewModel::class.java]
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {error=e.message ?: "无法加载预览"}
    }
    BackHandler(onBack=onBack)
    Scaffold(topBar={Row(Modifier.statusBarsPadding().height(60.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack) {Glyph("back",MaterialTheme.colorScheme.onSurface,Modifier.size(22.dp),"返回")}
        Text("手表预览",fontSize=18.sp,modifier=Modifier.weight(1f))
        IconButton(onClick={revision++}) {Glyph("repeat",MaterialTheme.colorScheme.primary,Modifier.size(22.dp),"重新加载脚本")}
    }}) {padding->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val diameter=minOf(300.dp,maxWidth-40.dp,maxHeight-100.dp).coerceAtLeast(160.dp)
            Column(Modifier.fillMaxSize().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Box(Modifier.size(diameter).border(4.dp,Color(0xFF383C39),CircleShape).padding(4.dp).clip(CircleShape).background(Color.Black)) {
                    model?.let {WatchScreen(it,preview=true,onHome=onBack)} ?: Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        if(error==null) CircularProgressIndicator() else Text(error!!,color=Color.White,modifier=Modifier.padding(24.dp),fontSize=13.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("加载手表栏目所选脚本，无需同步",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("变量与文件独立，硬件信息来自手机",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
