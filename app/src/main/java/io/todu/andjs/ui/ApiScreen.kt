package io.todu.andjs.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    BackHandler(onBack=onBack)
    Scaffold(containerColor=colors.background,topBar={Row(Modifier.then(if(embedded) Modifier else Modifier.statusBarsPadding()).fillMaxWidth().height(60.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=onBack) {Glyph(if(embedded && method==null) "x" else "back",colors.onSurface,Modifier.size(22.dp),if(embedded && method==null) "关闭" else "返回")}
        Text(method?.namespace ?: "接口声明",fontSize=18.sp,modifier=Modifier.weight(1f),maxLines=1)
        if(method!=null && onClose!=null) IconButton(onClick=onClose) {Glyph("x",colors.onSurface,Modifier.size(22.dp),"关闭")}
    }}) {padding -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(method!=null) {
            Text(method.signature,fontFamily=FontFamily.Monospace,fontSize=16.sp,color=colors.primary)
            Text(method.summary,fontSize=14.sp)
            Text(method.availability+" · "+method.devices,fontSize=12.sp,color=colors.onSurfaceVariant)
            Text("参数",fontWeight=FontWeight.SemiBold)
            method.arguments.forEach { arg -> Surface(shape=RoundedCornerShape(12.dp),color=colors.surface) {Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(arg.name+" · "+arg.type,fontFamily=FontFamily.Monospace,fontSize=13.sp);Text(arg.description,fontSize=13.sp,color=colors.onSurfaceVariant)
            }} }
            Text("返回值",fontWeight=FontWeight.SemiBold);Text(method.returns,fontSize=14.sp)
            method.fields.forEach {field->
                Surface(shape=RoundedCornerShape(12.dp),color=colors.surface) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(field.name+" · "+field.type,fontSize=13.sp,fontFamily=FontFamily.Monospace)
                        Text(field.description,fontSize=13.sp,color=colors.onSurfaceVariant)
                    }
                }
            }
            Text("示例",fontWeight=FontWeight.SemiBold)
            Surface(shape=RoundedCornerShape(12.dp),color=colors.surface) {Text(method.example,fontFamily=FontFamily.Monospace,fontSize=12.sp,modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp))}
        } else {
            OutlinedTextField(query,{query=it},placeholder={Text("搜索方法或用途",fontSize=14.sp)},leadingIcon={Glyph("search",colors.onSurfaceVariant,Modifier.size(20.dp))},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(category==null,{category=null},label={Text("全部")})
                ApiCategory.entries.forEach {type -> FilterChip(category==type.name,{category=type.name},label={Text(type.title)})}
            }
            val matches=ApiCatalog.methods.filter {(category==null || it.category.name==category) && (it.signature+it.summary+it.namespace).contains(query,true)}
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
                        Text(entry.summary,fontSize=13.sp)
                        Text(entry.devices,fontSize=11.sp,color=colors.onSurfaceVariant)
                    }
                } }
            }
        }
        Spacer(Modifier.height(20.dp))
    } }
}
