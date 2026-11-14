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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.todu.andjs.R
import io.todu.andjs.domain.*
import io.todu.andjs.ui.theme.*

@Composable fun HomeScreen(model: HomeViewModel) {
    val appearance=LocalAppearance.current
    var chooseAppearance by rememberSaveable {mutableStateOf(false)}
    if(chooseAppearance) AlertDialog(onDismissRequest={chooseAppearance=false},title={Text("外观")},text={
        Column {Appearance.entries.forEach {option ->
            Row(Modifier.fillMaxWidth().clickable {appearance.select(option);chooseAppearance=false}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=appearance.mode==option,onClick=null)
                Spacer(Modifier.width(12.dp));Text(option.title)
            }
        }}
    },confirmButton={},dismissButton={TextButton(onClick={chooseAppearance=false}) {Text("取消")}})
    val state by model.state.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val globals by model.globals.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var delete by remember { mutableStateOf<Script?>(null) }
    val execution by model.execution.collectAsStateWithLifecycle()
    val interaction by model.interaction.collectAsStateWithLifecycle()
    execution?.let { RunScreen(it,model::stopRun,model::closeRun,interaction) }
    val categories by model.categories.collectAsStateWithLifecycle()
    val managingCategories by model.managingCategories.collectAsStateWithLifecycle()
    LaunchedEffect(categories) {if(category!="全部" && categories.none {it.id==category}) category="全部"}
    LaunchedEffect(page) { if(page==1 && globals==null) model.openGlobals() }
    var watchPreview by rememberSaveable {mutableStateOf(false)}
    if(watchPreview) {WatchPreviewScreen(model) {watchPreview=false};return}
    var permissionsPage by rememberSaveable {mutableStateOf(false)}
    if(permissionsPage) {PermissionsScreen {permissionsPage=false};return}
    var licensesPage by rememberSaveable {mutableStateOf(false)}
    if(licensesPage) {LicenseScreen {licensesPage=false};return}
    var apiPage by rememberSaveable {mutableStateOf(false)}
    var apiMethod by rememberSaveable {mutableStateOf<String?>(null)}
    if(apiPage) {
        ApiScreen(apiMethod,onSelect={apiMethod=it},onBack={if(apiMethod!=null) apiMethod=null else apiPage=false})
        return
    }
    if(managingCategories) {
        CategoryScreen(categories,state.items,onBack={model.managingCategories.value=false},onSave=model::saveCategories)
        if(message!=null) AlertDialog(onDismissRequest=model::dismissMessage,text={Text(message!!)},confirmButton={TextButton(onClick=model::dismissMessage){Text("知道了")}})
        return
    }
    if(page==1 && globals!=null) {
        GlobalEditorScreen(globals!!,onNavigate={model.dismissMessage();page=it;model.closeGlobals()},onSave=model::saveGlobals,message=message,onDismissMessage=model::dismissMessage)
        return
    }
    if(page==2) {WatchPhoneScreen(model,onNavigate={page=it});return}
    if (editor != null) {
        ScriptEditorScreen(editor!!, categories, onDismiss=model::closeEditor, onSave=model::save,onRun={doc,updated->model.run(doc.item,doc.source,updated)})
        if (message != null) AlertDialog(onDismissRequest=model::dismissMessage, text={Text(message!!)}, confirmButton={TextButton(onClick=model::dismissMessage){Text("知道了")}})
        return
    }
    val colors = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var importing by remember {mutableStateOf(false)}
    val importFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) scope.launch {
            importing=true
            try {
                val script=withContext(Dispatchers.IO) {io.todu.andjs.data.readScriptImport(context.contentResolver,uri)}
                if(model.importScript(script.name,script.source,category)) {query="";category="全部"}
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {snackbar.showSnackbar(if(e is java.nio.charset.CharacterCodingException) "请使用 UTF-8 编码的 JS 文件" else e.message ?: "导入失败")}
            finally {importing=false}
        }
    }
    BackHandler(page != 0 && editor == null) { page = 0 }
    Scaffold(containerColor=colors.background, snackbarHost={ SnackbarHost(snackbar) }, bottomBar={
        AppNavigation(page) {page=it}
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().widthIn(max=840.dp).padding(horizontal=Dimensions.page)) {
            MainPageHeader(if(page==0) "And JS" else if(page==1) "全局变量" else "设置") {
                if(page==0) IconButton(onClick={
                    try {importFile.launch(arrayOf("*/*"))} catch(_:Exception) {scope.launch {snackbar.showSnackbar("此设备没有文件选择器")}}
                },enabled=!importing,modifier=Modifier.size(48.dp)) {Glyph("download",if(importing) colors.onSurface.copy(alpha=.38f) else colors.primary,Modifier.size(22.dp),"导入 JS")}
                if(page==0) IconButton(onClick=model::newScript, modifier=Modifier.size(48.dp)) { Glyph("plus",colors.primary,Modifier.size(23.dp),"新建脚本") }
            }
            when(page) {
                0 -> {
                    OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("搜索脚本",fontSize=14.sp)},leadingIcon={Glyph("search",colors.onSurfaceVariant,Modifier.size(20.dp))},shape=RoundedCornerShape(16.dp), textStyle=LocalTextStyle.current.copy(fontSize=14.sp), colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=colors.surfaceVariant,unfocusedBorderColor=Color.Transparent,focusedBorderColor=colors.primary))
                    Row(Modifier.fillMaxWidth().padding(vertical=12.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        (listOf(ScriptCategory("全部","全部"))+categories).forEach { entry ->
                            Column(Modifier.clickable {category=entry.id}.widthIn(min=48.dp).padding(top=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(entry.name,color=if(category==entry.id) colors.primary else colors.onSurfaceVariant,fontSize=14.sp,fontWeight=if(category==entry.id) FontWeight.SemiBold else FontWeight.Normal)
                                Spacer(Modifier.height(10.dp)); Box(Modifier.width(36.dp).height(2.dp).background(if(category==entry.id) colors.primary else Color.Transparent))
                            }
                        }
                        IconButton(onClick={model.managingCategories.value=true}) {Glyph("settings",colors.onSurfaceVariant,Modifier.size(18.dp),"管理分类")}
                    }
                    val items = state.items.filter { (category=="全部" || it.category==category) && it.name.contains(query,true) }
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                        state.error != null -> EmptyPanel(state.error!!,"重试",model::retry)
                        items.isEmpty() -> EmptyPanel(if(state.items.isEmpty()) "还没有脚本" else "没有匹配的脚本",if(state.items.isEmpty()) "新建脚本" else null,model::newScript)
                        else -> SortableScriptGrid(items,model::move,model::saveOrder) {item -> ScriptCard(item,onWidget={model.toggleWidget(item,it)},onRun={model.run(item)},onEdit={model.edit(item)},onDelete={delete=item})}

                    }
                }
                1 -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                3 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(top=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    SettingRow("管理分类",onClick={model.managingCategories.value=true})
                    SettingRow("接口声明",onClick={apiPage=true})
                    SettingRow("权限授权",onClick={permissionsPage=true})
                    SettingRow("手表预览",onClick={watchPreview=true})
                    SettingRow("外观",appearance.mode.title,onClick={chooseAppearance=true})
                    SettingRow("版本",io.todu.andjs.BuildConfig.VERSION_NAME)
                    SettingRow("开源许可",onClick={licensesPage=true})
                }
            }
        }
    }
    delete?.let { item -> AlertDialog(onDismissRequest={delete=null},title={Text("删除脚本？")},text={Text(item.name)},confirmButton={TextButton(onClick={model.delete(item);delete=null}) {Text("删除")}},dismissButton={TextButton(onClick={delete=null}) {Text("取消")}}) }
}

@Composable private fun EmptyPanel(title: String, action: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top=92.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Glyph("code",MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.55f),Modifier.size(32.dp))
        Text(title,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(action!=null) TextButton(onClick=onClick) {Text(action)}
    }
}
@Composable private fun SettingRow(title: String, value: String? = null, onClick: (() -> Unit)? = null) {
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier).padding(18.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(title,fontSize=14.sp)
            if(value != null) Text(value,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else Glyph("forward",MaterialTheme.colorScheme.onSurfaceVariant,Modifier.size(22.dp))
        }
    }
}
@Composable private fun ScriptCard(item: Script,onWidget:(Boolean)->Unit,onRun:()->Unit,onEdit:()->Unit,onDelete:()->Unit) {
    val colors=MaterialTheme.colorScheme
    var expanded by remember {mutableStateOf(false)}
    val iconColors=scriptColors(item.color)
    Surface(onClick=onRun,shape=RoundedCornerShape(Dimensions.cardRadius),color=colors.surface,border=BorderStroke(0.7.dp,colors.outlineVariant)) {
        Column(Modifier.heightIn(min=112.dp).padding(start=14.dp,end=6.dp,top=6.dp,bottom=6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(iconColors.background,RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {Glyph(item.icon,iconColors.foreground,Modifier.size(21.dp))}
                Spacer(Modifier.weight(1f))
                WidgetToggle(item.widget,onWidget)
                Box { IconButton(onClick={expanded=true}) {Glyph("more",colors.onSurfaceVariant,Modifier.size(20.dp),"${item.name}的更多操作")}
                    DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                        DropdownMenuItem(text={Text("编辑")},onClick={expanded=false;onEdit()})
                        DropdownMenuItem(text={Text("删除")},onClick={expanded=false;onDelete()})
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(item.name,fontSize=15.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            }
        }
    }
}


@Composable fun AppNavigation(page:Int,onSelect:(Int)->Unit) {
    val colors=MaterialTheme.colorScheme
    Column {
    HorizontalDivider(thickness=0.7.dp,color=colors.outlineVariant)
    NavigationBar(containerColor=colors.surface,tonalElevation=0.dp) {
        listOf("脚本" to "code","全局变量" to "global","手表" to "watch","设置" to "settings").forEachIndexed {index,(title,icon)->
            NavigationBarItem(selected=page==index,onClick={onSelect(index)},icon={Glyph(icon,if(page==index) colors.primary else colors.onSurfaceVariant,Modifier.size(21.dp))},label={Text(title,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=colors.primaryContainer))
        }
    }
}
}
