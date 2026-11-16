package io.todu.andjs.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.domain.*
@Composable fun ApiScreen(methodId:String?,onSelect:(String)->Unit,onBack:()->Unit,embedded:Boolean=false,onClose:(()->Unit)?=null) {
    var query by rememberSaveable {mutableStateOf("")}
    var category by rememberSaveable {mutableStateOf<String?>(null)}
    val method=ApiCatalog.methods.find {it.id==methodId}
    val colors=MaterialTheme.colorScheme
    val clipboard=LocalClipboardManager.current
    val scroll=rememberScrollState()
    val codeScroll=rememberScrollState()
    var copied by remember(methodId) {mutableStateOf<String?>(null)}
    LaunchedEffect(copied) {if(copied!=null) {kotlinx.coroutines.delay(1500);copied=null}}
    LaunchedEffect(methodId) {scroll.scrollTo(0);codeScroll.scrollTo(0)}
    BackHandler(onBack=onBack)
    Scaffold(containerColor=colors.background,topBar={Row(Modifier.then(if(embedded) Modifier else Modifier.statusBarsPadding()).fillMaxWidth().height(60.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack) {Glyph(if(embedded && method==null) "x" else "back",colors.onSurface,Modifier.size(22.dp),if(embedded && method==null) "关闭" else "返回")}
        Text(method?.namespace ?: "接口声明",fontSize=18.sp,modifier=Modifier.weight(1f),maxLines=1)
        if(method!=null && onClose!=null) IconButton(onClick=onClose) {Glyph("x",colors.onSurface,Modifier.size(22.dp),"关闭")}
    }}) {padding -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(horizontal=20.dp).padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(method!=null) {
            Surface(shape=RoundedCornerShape(18.dp),color=colors.primary.copy(alpha=.045f),border=BorderStroke(1.dp,colors.outlineVariant.copy(alpha=.45f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        SelectionContainer(Modifier.weight(1f).padding(top=8.dp)) {
                            Text(method.signature,fontFamily=FontFamily.Monospace,fontSize=16.sp,lineHeight=23.sp,fontWeight=FontWeight.Medium,color=colors.primary)
                        }
                        if(method.signature.contains('(')) IconButton(
                            onClick={clipboard.setText(AnnotatedString(method.signature.substringBefore('(')));copied="function"},
                            modifier=Modifier.size(40.dp).background(colors.primary.copy(alpha=.08f),RoundedCornerShape(12.dp))
                        ) {Glyph(if(copied=="function") "check" else "copy",colors.primary,Modifier.size(19.dp),if(copied=="function") "已复制函数名" else "复制函数名")}
                    }
                    Text(method.summary,fontSize=14.sp,lineHeight=21.sp,color=colors.onSurface)
                    Text(method.devices+" · "+method.availability,fontSize=11.sp,lineHeight=17.sp,color=colors.onSurfaceVariant)
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("使用示例",fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    TextButton(onClick={clipboard.setText(AnnotatedString(method.example));copied="example"}) {
                        Glyph(if(copied=="example") "check" else "copy",colors.primary,Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp));Text(if(copied=="example") "已复制" else "复制代码",fontSize=12.sp)
                    }
                }
                Surface(shape=RoundedCornerShape(14.dp),color=colors.surfaceContainerLow) {
                    SelectionContainer {Text(method.example,fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.fillMaxWidth().horizontalScroll(codeScroll).padding(16.dp))}
                }
            }
            if(method.usage.isNotEmpty()) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("使用说明",fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                    method.usage.forEach {Text(it,fontSize=13.sp,lineHeight=21.sp,color=colors.onSurfaceVariant)}
                }
            }
            Text("参数",fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=4.dp))
            if(method.arguments.isEmpty()) Text("无需参数",fontSize=13.sp,color=colors.onSurfaceVariant)
            val config=method.arguments.filter {it.name.startsWith("options.") || it.name.startsWith("defaults.") || it.name.startsWith("requests[].")}
            val basic=method.arguments-config.toSet()
            basic.forEach {ApiField(it)}
            if(config.isNotEmpty()) {
                var expanded by remember(method.id) {mutableStateOf(config.size<=5)}
                TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {
                    Text("配置项（${config.size}）");Spacer(Modifier.width(8.dp));Text(if(expanded) "收起" else "展开")
                }
                if(expanded) config.forEach {ApiField(it)}
            }
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("返回值与异常",fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=4.dp))
                Surface(shape=RoundedCornerShape(14.dp),color=colors.surfaceContainerLow) {
                    SelectionContainer {Text(method.returns,fontSize=13.sp,lineHeight=22.sp,modifier=Modifier.fillMaxWidth().padding(16.dp))}
                }
            }
            if(method.fields.isNotEmpty()) {
                var expanded by remember(method.id) {mutableStateOf(method.fields.size<=5)}
                TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {
                    Text("返回字段（${method.fields.size}）");Spacer(Modifier.width(8.dp));Text(if(expanded) "收起" else "展开")
                }
                if(expanded) method.fields.forEach {ApiField(it)}
            }
        } else {
            OutlinedTextField(query,{query=it},placeholder={Text("搜索方法或用途",fontSize=14.sp)},leadingIcon={Glyph("search",colors.onSurfaceVariant,Modifier.size(20.dp))},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(category==null,{category=null},label={Text("全部")})
                ApiCategory.entries.forEach {type -> FilterChip(category==type.name,{category=type.name},label={Text(type.title)})}
            }
            val matches=ApiCatalog.methods.filter {(category==null || it.category.name==category) && (it.signature+it.summary+it.namespace+it.usage.joinToString()+it.arguments.joinToString {a->a.name+" "+a.description}+it.returns+it.example).contains(query,true)}
            if(matches.isEmpty()) Text("暂无接口",fontSize=14.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(vertical=24.dp))
            matches.groupBy {it.category}.forEach { (group, methods) ->
                Text(group.title,fontSize=12.sp,color=colors.onSurfaceVariant)
                methods.forEach { entry -> Surface(onClick={onSelect(entry.id)},shape=RoundedCornerShape(16.dp),color=colors.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(entry.signature.substringBefore('('),fontSize=15.sp,fontFamily=FontFamily.Monospace,color=colors.primary,modifier=Modifier.weight(1f))
                            IconButton(onClick={clipboard.setText(AnnotatedString(entry.signature.substringBefore('(')))},modifier=Modifier.size(32.dp)) {
                                Glyph("copy",colors.onSurfaceVariant,Modifier.size(18.dp),"复制方法名")
                            }
                        }
                        Text(entry.summary,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(entry.devices,fontSize=11.sp,color=colors.onSurfaceVariant)
                    }
                } }
            }
        }
        Spacer(Modifier.height(20.dp))
    } }
}

@Composable private fun ApiField(field:ApiArgument) {
    val colors=MaterialTheme.colorScheme
    Surface(shape=RoundedCornerShape(12.dp),color=colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(field.name,fontFamily=FontFamily.Monospace,fontSize=14.sp,fontWeight=FontWeight.Medium,color=colors.onSurface)
            Text(field.type,fontSize=11.sp,color=colors.primary)
            Text(field.description,fontSize=13.sp,lineHeight=21.sp,color=colors.onSurfaceVariant)
        }
    }
}
