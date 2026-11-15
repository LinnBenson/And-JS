package io.todu.andjs.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler

@Composable fun WatchPhoneScreen(model:HomeViewModel,onNavigate:(Int)->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val widgets by model.watchWidgets.collectAsStateWithLifecycle()
    val ids by model.watchIds.collectAsStateWithLifecycle()
    val dirty by model.watchDirty.collectAsStateWithLifecycle()
    val busy by model.watchBusy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val notes by model.watchNotes.collectAsStateWithLifecycle()
    var noteIndex by rememberSaveable {mutableStateOf<Int?>(null)}
    var noteText by rememberSaveable {mutableStateOf("")}
    var savingNote by remember {mutableStateOf(false)}
    val colors=MaterialTheme.colorScheme
    val snackbar=remember {SnackbarHostState()}
    var choosing by rememberSaveable {mutableStateOf(false)}
    var selected by rememberSaveable {mutableStateOf(listOf<String>())}
    var query by rememberSaveable {mutableStateOf("")}
    LaunchedEffect(Unit) {model.refreshWatch();model.refreshWatchConnection()}
    LaunchedEffect(message) {message?.let {snackbar.showSnackbar(it);model.dismissMessage()}}
    BackHandler {if(choosing) choosing=false else onNavigate(0)}
    Scaffold(containerColor=colors.background,snackbarHost={SnackbarHost(snackbar)},bottomBar={if(!choosing) AppNavigation(2,onNavigate)}) {padding->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal=20.dp)) {
            MainPageHeader(if(choosing) "添加脚本" else "手表",onBack=if(choosing) ({choosing=false}) else null) {
                if(choosing) IconButton(onClick={model.selectWatch(selected);choosing=false},enabled=selected.size<=100) {Glyph("check",colors.primary,Modifier.size(22.dp),"完成选择")}
                else {
                    if(busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp),strokeWidth=2.dp)
                    else if(dirty) IconButton(onClick=model::syncWatch) {Glyph("repeat",colors.primary,Modifier.size(22.dp),"同步到手表")}
                    IconButton(onClick={selected=ids.filter {id->state.items.any {it.id==id}};query="";choosing=true},enabled=!busy) {Glyph("plus",colors.primary,Modifier.size(23.dp),"选择脚本")}
                }
            }
            if(choosing) {
                OutlinedTextField(query,{query=it},placeholder={Text("搜索脚本")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
                LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                    items(state.items.filter {it.name.contains(query,true)},key={it.id}) {item->
                        Surface(onClick={selected=if(item.id in selected) selected-item.id else if(selected.size<100) selected+item.id else selected},shape=RoundedCornerShape(14.dp),color=colors.surface) {
                            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp).heightIn(min=58.dp),verticalAlignment=Alignment.CenterVertically) {
                                Glyph(item.icon,scriptColors(item.color).foreground,Modifier.size(22.dp));Spacer(Modifier.width(12.dp))
                                Text(item.name,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                                Checkbox(item.id in selected,onCheckedChange=null)
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    notes.forEachIndexed {index,text->
                        FilterChip(selected=text.isNotEmpty(),onClick={noteText=text;noteIndex=index},enabled=!busy,
                            label={Text("便签 ${index+1}",maxLines=1)},modifier=Modifier.weight(1f))
                    }
                }
                val items=ids.mapNotNull {id->state.items.find {it.id==id}}
                if(items.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {Text("尚未添加脚本",color=colors.onSurfaceVariant)}
                else SortableList(items,keyOf={it.id},onMove={a,b->if(!busy) model.moveWatch(a,b)},modifier=Modifier.weight(1f),onDrop={model.selectWatch(model.watchIds.value)}) {item->
                    Surface(shape=RoundedCornerShape(14.dp),color=colors.surface) {
                        Row(Modifier.fillMaxWidth().heightIn(min=62.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            Glyph(item.icon,scriptColors(item.color).foreground,Modifier.size(22.dp));Spacer(Modifier.width(12.dp))
                            Text(item.name,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                            WidgetToggle(item.id in widgets,{model.toggleWatchWidget(item.id,it)},enabled=!busy)
                            IconButton(onClick={model.selectWatch(ids-item.id)},enabled=!busy) {Glyph("trash-2",colors.onSurfaceVariant,Modifier.size(20.dp),"从手表移除${item.name}")}
                        }
                    }
                }
            }
        }
    }
    noteIndex?.let {index->
        AlertDialog(onDismissRequest={if(!savingNote) noteIndex=null},title={Text("便签 ${index+1}")},
            text={OutlinedTextField(noteText,{if(it.length<=io.todu.andjs.wear.WatchSnapshot.MAX_NOTE_LENGTH) noteText=it},
                enabled=!savingNote,modifier=Modifier.fillMaxWidth().heightIn(min=160.dp,max=280.dp),
                placeholder={Text("输入内容")},supportingText={Text("${noteText.length}/4000")})},
            confirmButton={Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(enabled=!savingNote && noteText.isNotEmpty(),onClick={noteText=""}) {Text("清空")}
                Spacer(Modifier.weight(1f))
                TextButton(enabled=!savingNote,onClick={noteIndex=null}) {Text("取消")}
                TextButton(enabled=!savingNote,onClick={savingNote=true;model.saveWatchNote(index,noteText) {saved->savingNote=false;if(saved) noteIndex=null}}) {Text("保存")}
            }})
    }
}
