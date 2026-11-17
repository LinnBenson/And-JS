package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.saveable.rememberSaveable
import io.todu.andjs.R
import io.todu.andjs.domain.ScriptDocument
import kotlin.math.*
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
    var widgetPreview by rememberSaveable {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP && !(context as io.todu.andjs.AndJsApplication).voiceInputActive) model?.background()}
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
                override fun openHotspot(options:io.todu.andjs.runtime.HotspotOptions,control:RunControl):io.todu.andjs.runtime.HotspotSession=host.openHotspot(options,control)
                override fun hotspot(method:String):Map<String,Any?> = host.hotspot(method)
                override fun openHttpServer(port:Int,token:String,maxFileSizeMB:Int,control:RunControl,name:String,listen:String?):HttpServerSession=host.openHttpServer(port,token,maxFileSizeMB,control,name,listen)
                override fun openHttpChat(port:Int,token:String,maxFileSizeMB:Int,maxImageSizeMB:Int,control:RunControl,name:String,listen:String?):HttpServerSession=host.openHttpChat(port,token,maxFileSizeMB,maxImageSizeMB,control,name,listen)
                override fun call(namespace:String,method:String,args:org.json.JSONArray):Any? = when(namespace) {
                    "notes"->io.todu.andjs.runtime.ScriptNotes({store.state.value.notes},store::writeNote).call(method,args)
                    "cache"->ScriptCache(File(context.filesDir,"watch-preview/script-cache")).call(method,args)
                    "image"->LogImages.prepare(args.getString(0),true)
                    "files"->ScriptFiles(root,root,lowMemory=true) {false}.call(method,args)
                    "health"->if(method=="status") mapOf("sensors" to mapOf("steps" to false,"heartRate" to false,"stepsGranted" to false,"heartRateGranted" to false),"dailySteps" to false,"history" to mapOf("available" to false,"status" to "unsupported","granted" to emptyList<String>()),"source" to "preview") else error("请在真实设备上读取健康数据")
                    "permissions"->mapOf("internet" to true,"watch" to true,"writeSettings" to false,"sharedFiles" to false)
                    "system"->if(method=="share") ScriptExtras.share(context,args.getString(0)) {path->ScriptFiles(root,root,lowMemory=true) {false}.resolve(path)} else if(method.startsWith("set") || method in listOf("openSettings","vibrate","cancelVibration")) error("预览不修改手机系统设置") else host.call(namespace,method,args)
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
            val diameter=minOf(300.dp,maxWidth-40.dp,maxHeight-164.dp).coerceAtLeast(160.dp)
            Row(Modifier.align(Alignment.TopCenter).padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(selected=!widgetPreview,onClick={model?.background();widgetPreview=false},label={Text("主界面")})
                FilterChip(selected=widgetPreview,onClick={model?.background();widgetPreview=true},label={Text("小部件")})
            }
            Column(Modifier.fillMaxSize().padding(top=64.dp).padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Box(Modifier.size(diameter).border(4.dp,Color(0xFF383C39),CircleShape).padding(4.dp).clip(CircleShape).background(Color.Black)) {
                    model?.let {watch->
                        val snapshot by watch.snapshot.collectAsState()
                        val execution by watch.execution.collectAsState()
                        if(widgetPreview && execution==null) WatchWidgetPreview(snapshot.scripts.filter {it.item.widget}.take(8),
                            onRun={watch.run(it,fromWatchFace=true)},onOpen={widgetPreview=false})
                        else WatchScreen(watch,preview=true,onHome={watch.background();widgetPreview=true})
                    } ?: Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        if(error==null) CircularProgressIndicator() else Text(error!!,color=Color.White,modifier=Modifier.padding(24.dp),fontSize=13.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("加载手表栏目所选脚本，无需同步",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("变量独立，硬件与下载目录来自手机",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun WatchWidgetPreview(items:List<ScriptDocument>,onRun:(ScriptDocument)->Unit,onOpen:()->Unit) {
    val feedbackView=androidx.compose.ui.platform.LocalView.current
    var pressedIndex by remember(items) {mutableStateOf<Int?>(null)}
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        val diameter=minOf(maxWidth,maxHeight)
        val thickness=diameter.value*.21f
        val radius=(diameter.value-8f-thickness)/2f
        val gap=Math.toDegrees(3.0/radius).toFloat()
        Canvas(Modifier.fillMaxSize().pointerInput(items) {
            fun hit(point:Offset):Int? {
                val x=point.x-size.width/2f;val y=point.y-size.height/2f
                val distance=sqrt(x*x+y*y)
                val r=radius.dp.toPx()
                if(items.isEmpty() || abs(distance-r)>(thickness/2).dp.toPx()) return null
                val angle=((Math.toDegrees(atan2(x.toDouble(),-y.toDouble()))+360)%360).toFloat()
                val step=360f/items.size
                val index=if(items.size==1) 0 else (angle/step).toInt().coerceAtMost(items.lastIndex)
                val center=if(items.size==1) 0f else (index+.5f)*step
                val delta=abs((angle-center+540f)%360f-180f)
                return index.takeIf {delta<=(step-gap)/2f}
            }
            detectTapGestures(onPress={point->
                pressedIndex=hit(point)
                try {tryAwaitRelease()} finally {pressedIndex=null}
            },onTap={point->hit(point)?.let {index->
                feedbackView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);onRun(items[index])
            }})
        }) {
            val r=radius.dp.toPx()
            items.forEachIndexed {index,document->
                val sweep=360f/items.size-gap
                val angle=if(items.size==1) 0f else (index+.5f)*360f/items.size
                drawArc(Color(scriptColorPair(document.item.color).first).copy(alpha=if(pressedIndex==index) 1f else .85f),angle-90f-sweep/2f,sweep,false,
                    topLeft=Offset(center.x-r,center.y-r),size=Size(r*2,r*2),style=Stroke(thickness.dp.toPx(),cap=StrokeCap.Butt))
                drawIntoCanvas {canvas->
                    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color=scriptColorPair(document.item.color).second.toInt()
                        textSize=11.dp.toPx()
                        textAlign=android.graphics.Paint.Align.LEFT
                    }
                    val textRadius=r+(thickness/2-15).dp.toPx()
                    val textSweep=360f/items.size-gap-4f
                    val label=watchTileLabel(document.item.name,textSweep,textRadius/density)
                    val path=android.graphics.Path().apply {
                        addArc(center.x-textRadius,center.y-textRadius,center.x+textRadius,center.y+textRadius,angle-90f-textSweep/2f,textSweep)
                    }
                    canvas.nativeCanvas.drawTextOnPath(label,path,
                        ((Math.toRadians(textSweep.toDouble())*textRadius-paint.measureText(label))/2).toFloat(),0f,paint)
                }
            }
        }
        items.forEachIndexed {index,document->
            val angle=if(items.size==1) 0.0 else (index+.5)*2*PI/items.size
            val interaction=remember(document.item.id) {androidx.compose.foundation.interaction.MutableInteractionSource()}
            LaunchedEffect(interaction) {
                interaction.interactions.collect {event->
                    when(event) {
                        is androidx.compose.foundation.interaction.PressInteraction.Press->pressedIndex=index
                        is androidx.compose.foundation.interaction.PressInteraction.Release,
                        is androidx.compose.foundation.interaction.PressInteraction.Cancel->if(pressedIndex==index) pressedIndex=null
                    }
                }
            }
            Glyph(document.item.icon,Color(scriptColorPair(document.item.color).second),
                Modifier.offset((sin(angle)*(radius-thickness/2+16f)).dp,(-cos(angle)*(radius-thickness/2+16f)).dp).size(28.dp).clickable(interactionSource=interaction,indication=null) {feedbackView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);onRun(document)}.padding(4.dp),document.item.name)
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.clickable(onClick=onOpen)) {
            Image(painterResource(R.drawable.app_symbol),"打开 And JS",Modifier.size(width=50.dp,height=34.dp))
            Spacer(Modifier.height(4.dp))
            Text("v"+io.todu.andjs.BuildConfig.VERSION_NAME,color=Color(0xFFA6AFB7),fontSize=10.sp)
        }
    }
}
