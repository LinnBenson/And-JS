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

private val watchAccent=Color(0xFF8FAABE)
private val watchCard=Color(0xFF151B21)
private val watchContentShape=RoundedCornerShape(8.dp)
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
    if(run!=null) KeepRunScreenOn()
    BackHandler(enabled=noteIndex!=null) {noteIndex=null}
    MaterialTheme(colorScheme=io.todu.andjs.ui.theme.Dark.copy(background=Color.Black,surface=watchCard)) {
        Surface(Modifier.fillMaxSize(),color=Color.Black) {
            if(noteIndex!=null) {
                val index=noteIndex!!
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Keep the entire scrolling viewport inside the circular display.
                    val horizontalInset=maxWidth*.16f
                    val verticalInset=maxHeight*.16f
                    Box(Modifier.fillMaxSize().padding(start=horizontalInset,end=horizontalInset,top=verticalInset,bottom=maxOf(verticalInset,52.dp))) {
                        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(vertical=4.dp),
                            horizontalAlignment=Alignment.CenterHorizontally) {
                            item {androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(snapshot.notes[index],fontSize=14.sp,modifier=Modifier.fillMaxWidth())
                            }}
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom=4.dp),contentAlignment=Alignment.Center) {
                        WatchBackButton("关闭便签") {clickFeedback();noteIndex=null}
                    }
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
                            Glyph("shield",Color(0xFFA6AFB7),Modifier.size(18.dp),"权限授权")
                        }
                        snapshot.notes.forEachIndexed {index,text->
                            IconButton(enabled=text.isNotEmpty(),onClick={
                                clickFeedback();noteIndex=index
                                try {
                                    val clipboard=context.getSystemService(android.content.ClipboardManager::class.java)
                                    checkNotNull(clipboard).setPrimaryClip(android.content.ClipData.newPlainText("便签 ${index+1}",text))
                                } catch(_:Exception) {android.widget.Toast.makeText(context,"复制失败",android.widget.Toast.LENGTH_SHORT).show()}
                            },modifier=Modifier.weight(1f).height(40.dp).semantics {contentDescription="便签 ${index+1}"}) {
                                Text("${index+1}",fontSize=16.sp,color=if(text.isNotEmpty()) watchAccent else Color.DarkGray)
                            }
                        }
                        IconButton(onClick={clickFeedback();grid=!grid;preferences.edit().putBoolean("grid",grid).apply()},modifier=Modifier.weight(1f).height(40.dp)) {
                            Glyph(if(grid) "list" else "layout-grid",Color(0xFFA6AFB7),Modifier.size(18.dp),if(grid) "切换列表显示" else "切换宫格显示")
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
                                    Box(Modifier.size(42.dp).background(tint.background,watchContentShape),contentAlignment=Alignment.Center) {
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
                    Surface(onClick={clickFeedback();model.run(doc)},shape=watchContentShape,color=watchCard) {
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(tint.background,watchContentShape),contentAlignment=Alignment.Center) {Glyph(doc.item.icon,tint.foreground,Modifier.size(22.dp))}
                            Spacer(Modifier.width(10.dp));Text(doc.item.name,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                            Glyph("forward",Color.LightGray,Modifier.size(16.dp))
                        }
                    }
                }
            } else {
                val current=run!!
                fun returnFromRun() {
                    val returnToWatchFace=model.returnToWatchFace
                    model.close()
                    if(returnToWatchFace) onHome()
                }
                BackHandler {if(current.running) model.stop() else returnFromRun()}
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
                        if(current.running) CircularProgressIndicator(Modifier.size(30.dp),color=watchAccent,strokeWidth=3.dp)
                        else Glyph(if(current.result?.error!=null || current.result?.output=="执行失败") "x" else "check",if(current.result?.error!=null || current.result?.output=="执行失败") Color(0xFFE4AEAA) else watchAccent,Modifier.size(32.dp))
                    }
                    item {Text(if(current.running) current.name else if(current.result?.stopped==true) "已停止" else if(current.result?.error!=null || current.result?.output=="执行失败") "执行失败" else "执行完成",fontSize=16.sp,maxLines=2)}
                    itemsIndexed(current.logs) {_,line->if(line.installUri!=null) ApkLogItem(line) else if(line.level=="img") ImageLogItem(line) {previewImage=it} else WatchTextCard(line.text,when(line.level) {"warn"->Color(0xFFD5C197);"error"->Color(0xFFE4AEAA);else->Color.White})}
                    if(text.isNotEmpty()) item {WatchTextCard(text)}
                }
                Row(Modifier.fillMaxWidth().padding(bottom=4.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    if(current.running) {
                        IconButton(onClick={clickFeedback();model.stop()},modifier=Modifier.size(48.dp).semantics {contentDescription="停止脚本"}) {
                            Box(Modifier.size(32.dp).background(watchCard,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) {
                                Box(Modifier.size(12.dp).background(watchAccent,RoundedCornerShape(2.dp)))
                            }
                        }
                    } else {
                        WatchBackButton(if(model.returnToWatchFace) "返回表盘" else "返回列表") {clickFeedback();returnFromRun()}
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
    Surface(shape=watchContentShape,color=watchCard,modifier=Modifier.fillMaxWidth().clickable(onClickLabel="复制文本",onClick=copyTextAction(text,"日志与结果"))) {Text(text,color=color,fontSize=13.sp,modifier=Modifier.padding(12.dp))}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WatchInteraction(request:ScriptInteraction,embedded:Boolean=false) {
    key(request) {
        var text by rememberSaveable {mutableStateOf(request.initial)}
        var selected by rememberSaveable {mutableStateOf(listOf<Int>())}
        val context=androidx.compose.ui.platform.LocalContext.current
        val app=context.applicationContext as io.todu.andjs.AndJsApplication
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        val inputInteraction=remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
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
                BoxWithConstraints(Modifier.fillMaxSize()) {
                val horizontalInset=maxWidth*.12f
                val headingInset=maxWidth*.04f
                val verticalInset=maxHeight*.16f
                val footerHeight=52.dp
                Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
                LazyColumn(modifier=Modifier.weight(1f).fillMaxWidth().padding(start=horizontalInset,end=horizontalInset,bottom=maxOf(0.dp,verticalInset-footerHeight)),contentPadding=PaddingValues(top=verticalInset+4.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                    item {Text(request.title,fontSize=16.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=headingInset))}
                    if(request.content.isNotBlank()) item {Text(request.content,fontSize=12.sp,color=Color.LightGray,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=headingInset/2))}
                    if(request.kind=="input") item {androidx.compose.foundation.text.BasicTextField(text,{if(it.length<=10000) text=it},modifier=Modifier.fillMaxWidth().height(48.dp),textStyle=LocalTextStyle.current.copy(fontSize=14.sp,color=MaterialTheme.colorScheme.onSurface),maxLines=4,interactionSource=inputInteraction,cursorBrush=androidx.compose.ui.graphics.SolidColor(watchAccent),decorationBox={innerTextField->
                        OutlinedTextFieldDefaults.DecorationBox(value=text,innerTextField={Box(Modifier.height(48.dp),contentAlignment=Alignment.CenterStart) {innerTextField()}},enabled=true,singleLine=false,visualTransformation=androidx.compose.ui.text.input.VisualTransformation.None,interactionSource=inputInteraction,contentPadding=PaddingValues(horizontal=12.dp),container={
                            OutlinedTextFieldDefaults.Container(enabled=true,isError=false,interactionSource=inputInteraction,shape=watchContentShape)
                        },trailingIcon={
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
                        }) {Glyph("mic",watchAccent,Modifier.size(20.dp),"语音输入")}
                        })
                    })}
                    if(request.kind in listOf("select","multiSelect")) itemsIndexed(request.options) {index,label->
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Surface(onClick={if(request.kind=="select") request.respond(index) else selected=if(index in selected) selected-index else selected+index},shape=watchContentShape,color=if(index in selected) Color(0xFF344A5B) else watchCard) {
                            Row(Modifier.fillMaxWidth().heightIn(min=40.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {Text(label,fontSize=14.sp,modifier=Modifier.weight(1f));if(index in selected) Glyph("check",watchAccent,Modifier.size(18.dp))}
                        }
                        }
                    }

                }
                if(request.kind=="select") Box(Modifier.fillMaxWidth().padding(bottom=4.dp),contentAlignment=Alignment.Center) {
                    IconButton(onClick={request.respond(null)},modifier=Modifier.size(48.dp)) {
                        Box(Modifier.size(32.dp).background(watchCard,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) {
                            Glyph("x",watchAccent,Modifier.size(20.dp),"取消")
                        }
                    }
                } else Row(Modifier.fillMaxWidth().padding(top=4.dp).height(48.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Surface(onClick={request.respond(null)},modifier=Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(topStart=4.dp,topEnd=4.dp),color=MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize().padding(end=24.dp,bottom=4.dp),contentAlignment=Alignment.CenterEnd) {
                            Glyph("x",MaterialTheme.colorScheme.primary,Modifier.size(20.dp),"取消")
                        }
                    }
                    Surface(onClick={request.respond(when(request.kind) {"input"->text;"multiSelect"->selected.sorted();else->true})},modifier=Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(topStart=4.dp,topEnd=4.dp),color=MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize().padding(start=24.dp,bottom=4.dp),contentAlignment=Alignment.CenterStart) {
                            Glyph("check",MaterialTheme.colorScheme.primary,Modifier.size(20.dp),"确定")
                        }
                    }
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
