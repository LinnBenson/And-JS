package io.todu.andjs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.todu.andjs.runtime.ScriptInteraction

@Composable fun InteractionDialog(request:ScriptInteraction) {
    key(request) {
        var text by rememberSaveable {mutableStateOf(request.initial)}
        var selected by rememberSaveable {mutableStateOf(listOf<Int>())}
        val colors=MaterialTheme.colorScheme
        val overlay=LocalOverlayHost.current
        val cancel={request.respond(null)}
        val maxHeight=(LocalConfiguration.current.screenHeightDp*.8f).dp
        ModalHost(onDismiss=cancel) {
            Surface(modifier=Modifier.padding(horizontal=28.dp).widthIn(max=400.dp).fillMaxWidth().heightIn(max=maxHeight),shape=RoundedCornerShape(24.dp),color=colors.background,
                border=if(overlay) BorderStroke(1.dp,colors.outlineVariant) else null,
                shadowElevation=if(overlay) 12.dp else 0.dp) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).background(colors.primaryContainer,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                            Glyph(when(request.kind) {"input"->"pencil";"select"->"list";"multiSelect"->"list-checks";else->"check"},colors.primary,Modifier.size(22.dp))
                        }
                        Text(request.title,fontSize=18.sp,modifier=Modifier.weight(1f))
                    }
                    if(request.content.isNotBlank()) Text(request.content,fontSize=14.sp,color=colors.onSurfaceVariant,
                        modifier=Modifier.fillMaxWidth().heightIn(max=120.dp).verticalScroll(rememberScrollState()))
                    when(request.kind) {
                        "input"->OutlinedTextField(text,{if(it.length<=10000) text=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("请输入",fontSize=14.sp)},minLines=1,maxLines=5,shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=colors.surface,focusedContainerColor=colors.surface,unfocusedBorderColor=colors.outlineVariant))
                        "select","multiSelect"->LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(request.options) {index,label->
                                val checked=index in selected
                                val choose={if(request.kind=="select") request.respond(index) else selected=if(checked) selected-index else selected+index}
                                Surface(shape=RoundedCornerShape(12.dp),color=if(checked) colors.primaryContainer else colors.surface) {
                                    val selection=if(request.kind=="select") Modifier.selectable(checked,role=Role.RadioButton,onClick=choose) else Modifier.toggleable(checked,role=Role.Checkbox,onValueChange={choose()})
                                    Row(Modifier.fillMaxWidth().then(selection).heightIn(min=48.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                        Text(label,fontSize=14.sp,modifier=Modifier.weight(1f))
                                        if(request.kind=="multiSelect") Checkbox(checked,onCheckedChange=null)
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick=cancel,modifier=Modifier.weight(1f)) {Text("取消")}
                        if(request.kind!="select") Button(onClick={request.respond(when(request.kind) {"input"->text;"select"->selected.first();"multiSelect"->selected.sorted();else->true})},modifier=Modifier.weight(1f)) {Text("确定")}
                    }
                }
            }
        }
    }
}
