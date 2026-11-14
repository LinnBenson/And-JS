package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.domain.*
import io.todu.andjs.runtime.ParameterValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ScriptEditorScreen(document: ScriptDocument,categories:List<ScriptCategory>,onDismiss:()->Unit,onSave:(ScriptDocument)->Unit) {
    var name by rememberSaveable(document.item.id) {mutableStateOf(document.item.name)}
    var source by rememberSaveable(document.item.id) {mutableStateOf(document.source)}
    var category by rememberSaveable(document.item.id) {mutableStateOf(document.item.category)}
    var icon by rememberSaveable(document.item.id) {mutableStateOf(document.item.icon)}
    var parameters by rememberSaveable(document.item.id, stateSaver=listSaver<List<ScriptParameter>,String>(
        save={ list -> list.flatMap {listOf(it.key,it.type,it.value)} },
        restore={ list -> list.chunked(3).map {ScriptParameter(it[0],it[1],it[2])} }
    )) {mutableStateOf(document.item.parameters)}
    var codeFocused by remember {mutableStateOf(false)}
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
    BackHandler(enabled=codeFocused) {focusManager.clearFocus()}
    var showApi by rememberSaveable {mutableStateOf(false)}
    var apiMethod by rememberSaveable {mutableStateOf<String?>(null)}
    var tab by rememberSaveable {mutableIntStateOf(0)}
    var validation by remember {mutableStateOf<String?>(null)}
    var confirmDiscard by remember {mutableStateOf(false)}
    var picker by rememberSaveable {mutableStateOf<String?>(null)}
    var color by rememberSaveable(document.item.id) {mutableStateOf(document.item.color)}
    var parameterQuery by rememberSaveable {mutableStateOf("")}
    var parameterEdit by rememberSaveable {mutableStateOf<Int?>(null)}
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var transferring by remember {mutableStateOf(false)}
    var exportSource by rememberSaveable {mutableStateOf<String?>(null)}
    val snackbar=remember {SnackbarHostState()}
    val importFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) scope.launch {
            transferring=true
            try {
                val code=withContext(Dispatchers.IO) {
                    io.todu.andjs.data.readScriptImport(context.contentResolver,uri).source
                }
                source=code;tab=0;validation=null
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {validation=if(e is java.nio.charset.CharacterCodingException) "请使用 UTF-8 编码的 JS 文件" else e.message ?: "导入失败"}
            finally {transferring=false}
        }
    }
    val exportFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/javascript")) {uri->
        val code=exportSource;exportSource=null
        if(uri!=null && code!=null) scope.launch {
            transferring=true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(code.toByteArray(Charsets.UTF_8))} ?: error("无法写入文件")
                }
                snackbar.showSnackbar("已导出")
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {validation=e.message ?: "导出失败"}
            finally {transferring=false}
        }
    }
    fun chooseImport() {
        tab=0
        try {importFile.launch(arrayOf("*/*"))} catch(_:Exception) {validation="此设备没有文件选择器"}
    }
    fun chooseExport() {
        exportSource=source
        val filename=name.trim().replace(Regex("[\\/:*?\"<>|]"),"_").ifBlank {"main"}.removeSuffix(".js")+".js"
        try {exportFile.launch(filename)} catch(_:Exception) {exportSource=null;validation="此设备没有文件选择器"}
    }
    parameterEdit?.let {index ->
        ParameterEditScreen(parameters.getOrNull(index) ?: ScriptParameter(""), parameters, index,
            onBack={parameterEdit=null}, onSave={value ->
                parameters=parameters.toMutableList().apply {if(index<0) add(value) else set(index,value)}
                validation=null
                parameterEdit=null
            })
        return
    }
    val iconColors=scriptColors(color)
    val dirty=name!=document.item.name || source!=document.source || category!=document.item.category || icon!=document.item.icon || parameters!=document.item.parameters || color!=document.item.color
    val close={if(dirty) confirmDiscard=true else onDismiss()}
    BackHandler(onBack={if(codeFocused) focusManager.clearFocus() else close()})
    val colors=MaterialTheme.colorScheme
    val nameInteraction=remember {MutableInteractionSource()}
    val nameFocused by nameInteraction.collectIsFocusedAsState()
    if(showApi) androidx.compose.ui.window.Dialog(onDismissRequest={showApi=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).widthIn(max=640.dp).fillMaxHeight(.88f),shape=RoundedCornerShape(24.dp),color=colors.background) {
            ApiScreen(apiMethod,onSelect={apiMethod=it},onBack={if(apiMethod!=null) apiMethod=null else showApi=false},embedded=true,onClose={showApi=false})
        }
    }
    Scaffold(snackbarHost={SnackbarHost(snackbar)},containerColor=colors.background,modifier=Modifier.imePadding(),topBar={
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={if(codeFocused) focusManager.clearFocus() else close()}) {Glyph("back",colors.onSurface,Modifier.size(22.dp),"返回")}
            Text(if(document.item.name.isEmpty()) "新建脚本" else "编辑脚本",fontSize=18.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            IconButton(onClick={apiMethod=null;showApi=true}) {Glyph("help",colors.primary,Modifier.size(22.dp),"接口帮助")}
            IconButton(onClick=::chooseImport,enabled=!transferring) {Glyph("download",if(transferring) colors.onSurface.copy(alpha=.38f) else colors.primary,Modifier.size(22.dp),"导入 JS")}
            IconButton(onClick=::chooseExport,enabled=!transferring) {Glyph("upload",if(transferring) colors.onSurface.copy(alpha=.38f) else colors.primary,Modifier.size(22.dp),"导出 JS")}
            IconButton(onClick={validation=ParameterValues.error(parameters); if(validation==null) onSave(ScriptDocument(document.item.copy(name=name.trim(),category=category,icon=icon,parameters=parameters,color=color),source))},enabled=!transferring && name.isNotBlank() && name.length<=40) {Glyph("save",if(!transferring && name.isNotBlank() && name.length<=40) colors.primary else colors.onSurface.copy(alpha=.38f),Modifier.size(22.dp),"保存")}
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal=16.dp)) {
            androidx.compose.animation.AnimatedVisibility(!codeFocused) {Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                FilledIconButton(onClick={picker="appearance"},shape=RoundedCornerShape(12.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=iconColors.background,contentColor=iconColors.foreground)) {Glyph(icon,iconColors.foreground,Modifier.size(22.dp),"选择图标和颜色")}
                BasicTextField(name,{if(it.length<=40) name=it},singleLine=true,
                    interactionSource=nameInteraction,cursorBrush=SolidColor(colors.primary),
                    textStyle=LocalTextStyle.current.copy(color=colors.onSurface,fontSize=14.sp),
                    modifier=Modifier.weight(1f).height(40.dp).border(if(nameFocused) 2.dp else 1.dp,if(nameFocused) colors.primary else colors.outline,RoundedCornerShape(12.dp)).padding(horizontal=14.dp),
                    decorationBox={inner->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.CenterStart) {
                        if(name.isEmpty()) Text("脚本名称",fontSize=14.sp,color=colors.onSurfaceVariant)
                        inner()
                    }})
                TextButton(onClick={picker="category"}) {Text(categories.find {it.id==category}?.name ?: "未分类",fontSize=12.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=80.dp))}
            }
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=tab==0,onClick={tab=0},label={Text("代码")})
                FilterChip(selected=tab==1,onClick={tab=1},label={Text("参数" + if(parameters.isEmpty()) "" else " · ${parameters.size}")})
            }
            }}
            validation?.let { Text(it,color=colors.error,fontSize=12.sp) }
            Spacer(Modifier.height(6.dp))
            if(tab==1) ParameterList(parameters,parameterQuery,{parameterQuery=it},
                onAdd={parameterEdit=-1},onEdit={parameterEdit=it},
                onDelete={index->parameters=parameters.filterIndexed {i,_->i!=index};validation=null},
                modifier=Modifier.weight(1f).fillMaxWidth())
            if(tab==0) Surface(modifier=Modifier.weight(1f).fillMaxWidth().padding(bottom=12.dp),shape=RoundedCornerShape(16.dp),border=BorderStroke(0.7.dp,colors.outlineVariant)) {
                key(document.item.id) {CodeEditor(initialText=source,onChange={source=it},modifier=Modifier.fillMaxSize(),onFocus={codeFocused=it})}
            }
        }
    }
    if(picker=="appearance") AppearancePicker(icon,color,onIcon={icon=it},onColor={color=it},onDismiss={picker=null})
    if(picker=="category") CategoryPicker(category,categories,onSelect={category=it;picker=null},onDismiss={picker=null})
    if(confirmDiscard) AlertDialog(onDismissRequest={confirmDiscard=false},title={Text("放弃修改？")},confirmButton={TextButton(onClick=onDismiss){Text("放弃")}},dismissButton={TextButton(onClick={confirmDiscard=false}){Text("继续编辑")}})
}
