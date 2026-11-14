package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.domain.*
import java.util.UUID

@Composable fun CategoryScreen(initial:List<ScriptCategory>,items:List<Script>,onBack:()->Unit,onSave:(List<ScriptCategory>)->Unit) {
    var values by rememberSaveable(stateSaver=listSaver<List<ScriptCategory>,String>(save={it.flatMap {listOf(it.id,it.name)}},restore={it.chunked(2).map {ScriptCategory(it[0],it[1])}})) {mutableStateOf(initial)}
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ScriptCategory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember {mutableStateOf(false)}
    val colors=MaterialTheme.colorScheme
    val original=values.find {it.id==editing}?.name.orEmpty()
    val back={if(editing!=null) {if(name!=original) discard=true else editing=null} else if(values!=initial) discard=true else onBack()}
    BackHandler(onBack=back)
    Scaffold(containerColor=colors.background,modifier=Modifier.imePadding(),topBar={
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back){Glyph("back",colors.onSurface,Modifier.size(22.dp),"返回")}
            Text(if(editing==null) "管理分类" else if(editing=="") "新建分类" else "编辑分类",fontSize=18.sp,modifier=Modifier.weight(1f))
            if(editing==null) IconButton(onClick={editing="";name="";error=null}) {Glyph("plus",colors.primary,Modifier.size(23.dp),"添加分类")}
            TextButton(onClick={
                if(editing==null) onSave(values) else {
                    val clean=name.trim()
                    error=when {
                        clean.isEmpty()->"分类名称不能为空"
                        values.any {it.id!=editing && it.name==clean}->"分类名称已存在"
                        else->null
                    }
                    if(error==null) {
                        values=if(editing=="") values+ScriptCategory(UUID.randomUUID().toString(),clean) else values.map {if(it.id==editing) it.copy(name=clean) else it}
                        editing=null
                    }
                }
            }) {Text("保存")}
        }
    }) {padding->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(editing!=null) {
                OutlinedTextField(name,{if(it.length<=20) {name=it;error=null}},label={Text("分类名称")},singleLine=true,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth())
            } else {
                SortableList(values, keyOf={it.id}, onMove={from,to ->
                    val start=values.indexOfFirst {it.id==from}
                    val end=values.indexOfFirst {it.id==to}
                    if(start>=0 && end>=0 && start!=end) values=values.toMutableList().apply {add(end,removeAt(start))}
                }, modifier=Modifier.weight(1f)) {category->
                        Surface(shape=RoundedCornerShape(14.dp),color=colors.surface) {
                            Row(Modifier.fillMaxWidth().heightIn(min=62.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                Glyph("folder",colors.primary,Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(category.name,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                                Text(items.count {it.category==category.id}.toString(),fontSize=12.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(horizontal=8.dp))
                                IconButton(onClick={editing=category.id;name=category.name;error=null}) {Glyph("pencil",colors.onSurfaceVariant,Modifier.size(19.dp),"编辑${category.name}")}
                                IconButton(onClick={if(items.any {it.category==category.id}) error="请先移出该分类中的脚本" else deleting=category}) {Glyph("trash-2",colors.onSurfaceVariant,Modifier.size(19.dp),"删除${category.name}")}
                            }
                        }
                }
                if(values.isEmpty()) Text("暂无分类",color=colors.onSurfaceVariant,fontSize=14.sp)

            }
            error?.let {Text(it,color=colors.error,fontSize=13.sp,modifier=Modifier.padding(bottom=16.dp))}
        }
    }
    deleting?.let {category->AlertDialog(onDismissRequest={deleting=null},title={Text("删除分类？")},text={Text(category.name)},confirmButton={TextButton(onClick={values=values.filter {it.id!=category.id};deleting=null;error=null}) {Text("删除")}},dismissButton={TextButton(onClick={deleting=null}) {Text("取消")}})}
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("放弃修改？")},confirmButton={TextButton(onClick={discard=false;if(editing!=null) {editing=null;error=null} else onBack()}){Text("放弃")}},dismissButton={TextButton(onClick={discard=false}){Text("继续编辑")}})
}
