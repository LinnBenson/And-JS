package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import io.todu.andjs.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.todu.andjs.runtime.ScriptInteraction
import io.todu.andjs.wear.WatchViewModel

private val watchGreen=Color(0xFFB6FF80)
private val watchCard=Color(0xFF20242B)
@Composable fun WatchScreen(model:WatchViewModel,preview:Boolean=false,onHome:()->Unit) {
    var previewImage by remember {mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current.applicationContext
    val preferences=remember(context,preview) {context.getSharedPreferences(if(preview) "watch-preview-display" else "watch-display",android.content.Context.MODE_PRIVATE)}
    var grid by remember(preferences) {mutableStateOf(preferences.getBoolean("grid",true))}
    val feedbackView=androidx.compose.ui.platform.LocalView.current
    fun clickFeedback() {feedbackView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)}
    var permissions by rememberSaveable {mutableStateOf(false)}
    var noteIndex by rememberSaveable {mutableStateOf<Int?>(null)}
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val run by model.execution.collectAsStateWithLifecycle()
    val interaction by model.interaction.collectAsStateWithLifecycle()
    BackHandler(enabled=noteIndex!=null) {noteIndex=null}
    MaterialTheme(colorScheme=darkColorScheme(primary=watchGreen,onPrimary=Color.Black,background=Color.Black,surface=watchCard,onSurface=Color.White)) {
        Surface(Modifier.fillMaxSize(),color=Color.Black) {
            if(noteIndex!=null) {
                val index=noteIndex!!
                LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp,vertical=28.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    item {IconButton(onClick={noteIndex=null}) {Glyph("back",watchGreen,Modifier.size(22.dp),"关闭便签")}}
                    item {androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(snapshot.notes[index],fontSize=14.sp,modifier=Modifier.fillMaxWidth())
                    }}
                }
            }
            else if(permissions) WatchPermissionsScreen(preview) {permissions=false}
            else if(preview && interaction!=null) WatchInteraction(interaction!!,embedded=true)
            else if(run==null) LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                item {Image(painterResource(R.drawable.app_symbol),contentDescription="And JS",modifier=Modifier.padding(bottom=8.dp).size(width=44.dp,height=30.dp))}
                item {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        IconButton(onClick={clickFeedback();permissions=true},modifier=Modifier.weight(1f).height(40.dp)) {
                            Glyph("shield",Color(0xFF9CA497),Modifier.size(18.dp),"权限授权")
                        }
                        snapshot.notes.forEachIndexed {index,text->
                            IconButton(enabled=text.isNotEmpty(),onClick={
                                clickFeedback();noteIndex=index
                                try {
                                    val clipboard=context.getSystemService(android.content.ClipboardManager::class.java)
                                    checkNotNull(clipboard).setPrimaryClip(android.content.ClipData.newPlainText("便签 ${index+1}",text))
                                } catch(_:Exception) {android.widget.Toast.makeText(context,"复制失败",android.widget.Toast.LENGTH_SHORT).show()}
                            },modifier=Modifier.weight(1f).height(40.dp).semantics {contentDescription="便签 ${index+1}"}) {
                                Text("${index+1}",fontSize=16.sp,color=if(text.isNotEmpty()) watchGreen else Color.DarkGray)
                            }
                        }
                        IconButton(onClick={clickFeedback();grid=!grid;preferences.edit().putBoolean("grid",grid).apply()},modifier=Modifier.weight(1f).height(40.dp)) {
                            Glyph(if(grid) "list" else "layout-grid",Color(0xFF9CA497),Modifier.size(18.dp),if(grid) "切换列表显示" else "切换宫格显示")
                        }
                    }
                    }
                }
                if(snapshot.scripts.isEmpty()) item {Text("暂无脚本",fontSize=14.sp,color=Color.Gray,modifier=Modifier.padding(vertical=20.dp))}
                if(grid) {
                    items(snapshot.scripts.chunked(3),key={row->"grid:"+row.first().item.id}) {row->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            row.forEach {doc->
                                val tint=scriptColors(doc.item.color)
                                Column(Modifier.weight(1f).clickable {clickFeedback();model.run(doc)}.padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Box(Modifier.size(42.dp).background(tint.background,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {
                                        Glyph(doc.item.icon,tint.foreground,Modifier.size(25.dp))
                                    }
                                    Text(watchGridName(doc.item.name),fontSize=10.sp,lineHeight=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=5.dp))
                                }
                            }
                            repeat(3-row.size) {Spacer(Modifier.weight(1f))}
                        }
                    }
                } else items(snapshot.scripts,key={it.item.id}) {doc->
                    val tint=scriptColors(doc.item.color)
                    Surface(onClick={clickFeedback();model.run(doc)},shape=RoundedCornerShape(24.dp),color=watchCard) {
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(tint.background,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {Glyph(doc.item.icon,tint.foreground,Modifier.size(22.dp))}
                            Spacer(Modifier.width(10.dp));Text(doc.item.name,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                            Glyph("forward",Color.LightGray,Modifier.size(16.dp))
                        }
                    }
                }
            } else {
                val current=run!!
                BackHandler {if(current.running) model.stop() else model.close()}
                val list=rememberLazyListState()
                val text=current.result?.error ?: current.result?.output.orEmpty()
                val scrollUpdates=remember {Channel<Unit>(Channel.CONFLATED)}
                LaunchedEffect(current.logs,current.result,current.running) {scrollUpdates.trySend(Unit)}
                LaunchedEffect(list) {
                    for(update in scrollUpdates) {
                        // Wait for the new rows to be measured before following the tail.
                        withFrameNanos { }
                        withFrameNanos { }
                        while(list.canScrollForward) {
                            try {
                                val layout=list.layoutInfo
                                val tail=layout.visibleItemsInfo.lastOrNull()
                                val distance=if(tail!=null && tail.index==layout.totalItemsCount-1)
                                    (tail.offset+tail.size+layout.afterContentPadding-layout.viewportEndOffset).toFloat().coerceAtLeast(1f)
                                else layout.viewportSize.height.toFloat().coerceAtLeast(1f)
                                val moved=list.animateScrollBy(distance,tween(420,easing=LinearOutSlowInEasing))
                                if(moved<.5f) break
                            } catch(e:CancellationException) {
                                coroutineContext.ensureActive()
                                // Let a manual swipe take over until another update arrives.
                                break
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
                LazyColumn(modifier=Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(start=24.dp,end=24.dp,top=28.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    item {
                        if(current.running) CircularProgressIndicator(Modifier.size(30.dp),color=watchGreen,strokeWidth=3.dp)
                        else Glyph(if(current.result?.error!=null || current.result?.output=="执行失败") "x" else "check",if(current.result?.error!=null || current.result?.output=="执行失败") Color(0xFFFF8B83) else watchGreen,Modifier.size(32.dp))
                    }
                    item {Text(if(current.running) current.name else if(current.result?.stopped==true) "已停止" else if(current.result?.error!=null || current.result?.output=="执行失败") "执行失败" else "执行完成",fontSize=16.sp,maxLines=2)}
                    itemsIndexed(current.logs) {_,line->if(line.level=="img") ImageLogItem(line) {previewImage=it} else WatchTextCard(line.text,when(line.level) {"warn"->Color(0xFFFFD38A);"error"->Color(0xFFFF8B83);else->Color.White})}
                    if(text.isNotEmpty()) item {WatchTextCard(text)}
                    if(!current.running) item {
                        Button(onClick={clickFeedback();model.close();onHome()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("返回表盘",fontSize=14.sp)}
                    }
                }
                Box(Modifier.fillMaxWidth().padding(bottom=4.dp),contentAlignment=Alignment.Center) {
                    IconButton(onClick={clickFeedback();if(current.running) model.stop() else model.close()},modifier=Modifier.size(48.dp).semantics {contentDescription=if(current.running) "停止脚本" else "返回列表"}) {
                        Box(Modifier.size(32.dp).background(watchCard,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) {
                            if(current.running) Box(Modifier.size(12.dp).background(watchGreen,RoundedCornerShape(2.dp)))
                            else Glyph("back",watchGreen,Modifier.size(20.dp))
                        }
                    }
                }
                }
            }
        }
        previewImage?.let {ImagePreview(it,watch=true) {previewImage=null}}
        if(!preview) interaction?.let {WatchInteraction(it)}
    }
}
@Composable private fun WatchTextCard(text:String,color:Color=Color.White) {
    Surface(shape=RoundedCornerShape(16.dp),color=watchCard,modifier=Modifier.fillMaxWidth()) {Text(text,color=color,fontSize=13.sp,modifier=Modifier.padding(12.dp))}
}
@Composable private fun WatchInteraction(request:ScriptInteraction,embedded:Boolean=false) {
    key(request) {
        var text by rememberSaveable {mutableStateOf(request.initial)}
        var selected by rememberSaveable {mutableStateOf(listOf<Int>())}
        val context=androidx.compose.ui.platform.LocalContext.current
        val app=context.applicationContext as io.todu.andjs.AndJsApplication
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        var voicePending by rememberSaveable {mutableStateOf(false)}
        val voice=androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) {result->
            app.voiceInputActive=false
            app.externalFlow=false
            voicePending=false
            if(result.resultCode==android.app.Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.takeIf {it.isNotBlank()}?.let {text=it.take(10000)}
            }
        }
        DisposableEffect(request) {onDispose {app.voiceInputActive=false}}
        val content:@Composable ()->Unit = {
            Surface(Modifier.fillMaxSize(),color=Color.Black) {
                LazyColumn(modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                    item {Text(request.title,fontSize=16.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())}
                    if(request.content.isNotBlank()) item {Text(request.content,fontSize=12.sp,color=Color.LightGray,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())}
                    if(request.kind=="input") item {OutlinedTextField(text,{if(it.length<=10000) text=it},modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(fontSize=14.sp),maxLines=4,shape=RoundedCornerShape(16.dp),trailingIcon={
                        IconButton(enabled=!voicePending,onClick={
                            focus.clearFocus()
                            app.voiceInputActive=true
                            app.externalFlow=true
                            voicePending=true
                            try {
                                voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,request.title)
                                    putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS,1)
                                })
                            } catch(_:Exception) {
                                app.voiceInputActive=false;app.externalFlow=false;voicePending=false
                                android.widget.Toast.makeText(context,"无法打开系统语音输入",android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {Glyph("mic",watchGreen,Modifier.size(20.dp),"语音输入")}
                    })}
                    if(request.kind in listOf("select","multiSelect")) itemsIndexed(request.options) {index,label->
                        Surface(onClick={if(request.kind=="select") request.respond(index) else selected=if(index in selected) selected-index else selected+index},shape=RoundedCornerShape(20.dp),color=if(index in selected) Color(0xFF35532B) else watchCard) {
                            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {Text(label,fontSize=14.sp,modifier=Modifier.weight(1f));if(index in selected) Glyph("check",watchGreen,Modifier.size(18.dp))}
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={request.respond(null)},modifier=Modifier.weight(1f)) {Text("取消",maxLines=1)}
                            if(request.kind!="select") Button(onClick={request.respond(when(request.kind) {"input"->text;"multiSelect"->selected.sorted();else->true})},modifier=Modifier.weight(1f)) {Text("确定",maxLines=1)}
                        }
                    }
                }
            }
        }
        if(embedded) {BackHandler {request.respond(null)};content()}
        else Dialog(onDismissRequest={request.respond(null)},properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {content()}
    }
}

private fun watchGridName(name:String):String = if(name.codePointCount(0,name.length)>6) name.substring(0,name.offsetByCodePoints(0,6))+"…" else name
