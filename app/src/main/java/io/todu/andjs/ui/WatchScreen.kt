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
    var permissions by rememberSaveable {mutableStateOf(false)}
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val run by model.execution.collectAsStateWithLifecycle()
    val interaction by model.interaction.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme=darkColorScheme(primary=watchGreen,onPrimary=Color.Black,background=Color.Black,surface=watchCard,onSurface=Color.White)) {
        Surface(Modifier.fillMaxSize(),color=Color.Black) {
            if(permissions) WatchPermissionsScreen(preview) {permissions=false}
            else if(preview && interaction!=null) WatchInteraction(interaction!!,embedded=true)
            else if(run==null) LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                item {Text("And JS",fontSize=16.sp,modifier=Modifier.padding(bottom=8.dp))}
                item {OutlinedButton(onClick={permissions=true},modifier=Modifier.fillMaxWidth()) {Text("权限授权",fontSize=13.sp)}}
                if(snapshot.scripts.isEmpty()) item {Text("暂无脚本",fontSize=14.sp,color=Color.Gray,modifier=Modifier.padding(vertical=20.dp))}
                items(snapshot.scripts,key={it.item.id}) {doc->
                    val tint=scriptColors(doc.item.color)
                    Surface(onClick={model.run(doc)},shape=RoundedCornerShape(24.dp),color=watchCard) {
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
                    itemsIndexed(current.logs) {_,line->if(line.level=="img") ImageLogItem(line) else WatchTextCard(line.text,when(line.level) {"warn"->Color(0xFFFFD38A);"error"->Color(0xFFFF8B83);else->Color.White})}
                    if(text.isNotEmpty()) item {WatchTextCard(text)}
                    if(!current.running) item {
                        Button(onClick={model.close();onHome()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("返回表盘",fontSize=14.sp)}
                    }
                }
                Box(Modifier.fillMaxWidth().padding(bottom=4.dp),contentAlignment=Alignment.Center) {
                    IconButton(onClick={if(current.running) model.stop() else model.close()},modifier=Modifier.size(48.dp).semantics {contentDescription=if(current.running) "停止脚本" else "返回列表"}) {
                        Box(Modifier.size(32.dp).background(watchCard,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) {
                            if(current.running) Box(Modifier.size(12.dp).background(watchGreen,RoundedCornerShape(2.dp)))
                            else Glyph("back",watchGreen,Modifier.size(20.dp))
                        }
                    }
                }
                }
            }
        }
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
        val content:@Composable ()->Unit = {
            Surface(Modifier.fillMaxSize(),color=Color.Black) {
                LazyColumn(modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                    item {Text(request.title,fontSize=16.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())}
                    if(request.content.isNotBlank()) item {Text(request.content,fontSize=12.sp,color=Color.LightGray,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())}
                    if(request.kind=="input") item {OutlinedTextField(text,{if(it.length<=10000) text=it},modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(fontSize=14.sp),maxLines=4,shape=RoundedCornerShape(16.dp))}
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
