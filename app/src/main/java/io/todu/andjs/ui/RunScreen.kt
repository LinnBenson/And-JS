package io.todu.andjs.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun RunScreen(run:HomeViewModel.Execution,onStop:()->Unit,onClose:()->Unit,interaction:io.todu.andjs.runtime.ScriptInteraction?=null) {
    val colors=MaterialTheme.colorScheme
    val overlay=LocalOverlayHost.current
    val tint=scriptColors(run.color)
    val warningColor=Color(0xFF8A5000)
    val logState=rememberLazyListState()
    var followLogs by remember {mutableStateOf(true)}
    LaunchedEffect(logState) {
        snapshotFlow {logState.isScrollInProgress to logState.canScrollForward}.collect {(scrolling,forward)->
            if(scrolling) followLogs=!forward
        }
    }
    LaunchedEffect(run.logs.size) {
        if(followLogs && run.logs.isNotEmpty()) logState.scrollToItem(run.logs.lastIndex)
    }
    val hasLogs=run.logs.isNotEmpty()
    val resultText=run.result?.error ?: run.result?.output.orEmpty()
    val hasResult=resultText.isNotEmpty()
    val height=(LocalConfiguration.current.screenHeightDp * .84f).dp.coerceAtMost(620.dp)
    ModalHost(onDismiss={if(!run.running) onClose()},backEnabled=!run.running) {
        Surface(modifier=Modifier.padding(horizontal=24.dp).widthIn(max=440.dp).fillMaxWidth().height(height),shape=RoundedCornerShape(24.dp),color=colors.background,
            border=if(overlay) BorderStroke(1.dp,colors.outlineVariant) else null,
            shadowElevation=if(overlay) 12.dp else 0.dp) {
            Column(Modifier.fillMaxSize().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(run.name,fontSize=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Box(Modifier.size(104.dp),contentAlignment=Alignment.Center) {
                    if(run.running) CircularProgressIndicator(modifier=Modifier.fillMaxSize(),color=tint.foreground,trackColor=tint.background,strokeWidth=3.dp)
                    Box(Modifier.size(62.dp).background(tint.background,RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center) {
                        Glyph(run.icon,tint.foreground,Modifier.size(32.dp))
                    }
                }
                if(hasLogs) {
                    LazyColumn(modifier=Modifier.fillMaxWidth().then(if(hasResult) Modifier.heightIn(max=height*.32f) else Modifier.weight(1f)),state=logState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(run.logs,key={index,_->index}) {_,line->
                            Surface(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp),color=Color.White) {
                                if(line.level=="img") ImageLogItem(line) else SelectionContainer {Text(line.text,color=when(line.level) {"warn"->warningColor;"error"->Color(0xFFB3261E);else->Color(0xFF181C17)},modifier=Modifier.padding(horizontal=12.dp,vertical=10.dp),fontSize=12.sp,fontFamily=FontFamily.Monospace)}
                            }
                        }
                    }
                }
                if(hasResult) {
                    Surface(modifier=Modifier.fillMaxWidth().weight(1f),shape=RoundedCornerShape(10.dp),color=Color.White) {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SelectionContainer {
                                Text(resultText,fontSize=14.sp,fontFamily=FontFamily.Monospace,color=if(run.result?.error!=null) Color(0xFFB3261E) else Color(0xFF181C17),modifier=Modifier.padding(horizontal=12.dp,vertical=10.dp))
                            }
                        }
                    }
                }
                if(!hasLogs && !hasResult) Spacer(Modifier.weight(1f))
                if(run.running) OutlinedButton(onClick=onStop,modifier=Modifier.widthIn(min=120.dp)) {Text("停止")}
                else Button(onClick=onClose,modifier=Modifier.widthIn(min=120.dp)) {Text("关闭")}
            }
        }
    }
    if(interaction!=null) InteractionDialog(interaction)

}
