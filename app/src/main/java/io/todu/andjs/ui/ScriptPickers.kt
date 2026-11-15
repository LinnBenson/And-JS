package io.todu.andjs.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.domain.ScriptCategory

data class ScriptColors(val background:Color,val foreground:Color)
val scriptColorOptions=listOf(
    "mint" to "薄荷", "emerald" to "翡翠", "teal" to "青绿", "cyan" to "冰蓝", "blue" to "天空", "cobalt" to "钴蓝",
    "indigo" to "靛蓝", "violet" to "紫藤", "lavender" to "薰衣草", "purple" to "葡萄", "pink" to "樱粉", "rose" to "玫瑰",
    "red" to "珊瑚红", "peach" to "蜜桃", "orange" to "杏橙", "amber" to "琥珀", "sand" to "沙金", "lemon" to "柠檬",
    "lime" to "青柠", "olive" to "橄榄", "brown" to "可可", "taupe" to "暖灰", "slate" to "蓝灰", "gray" to "石墨",
)
fun scriptColorPair(id:String):Pair<Long,Long> = when(id) {
        "emerald" -> 0xFFD1F2DE to 0xFF176342
        "teal" -> 0xFFCCEFEB to 0xFF17645D
        "cyan" -> 0xFFD2F2FA to 0xFF176479
        "cobalt" -> 0xFFD7E3FF to 0xFF3154A0
        "indigo" -> 0xFFE0E1FA to 0xFF484A91
        "lavender" -> 0xFFEAE6FF to 0xFF64549C
        "purple" -> 0xFFF0D9F6 to 0xFF7B358B
        "pink" -> 0xFFFFDEF0 to 0xFF94376C
        "red" -> 0xFFFFDAD6 to 0xFF9A3831
        "peach" -> 0xFFFFDFD0 to 0xFF945039
        "amber" -> 0xFFFFE6B4 to 0xFF855812
        "lemon" -> 0xFFF8F3BE to 0xFF716614
        "lime" -> 0xFFEBF5C4 to 0xFF57691C
        "olive" -> 0xFFE4E9CF to 0xFF5B663A
        "brown" -> 0xFFEBDDD1 to 0xFF74513B
        "taupe" -> 0xFFEAE3DD to 0xFF6C5D51
        "slate" -> 0xFFDEE6EF to 0xFF4A6078
        "blue" -> 0xFFDDEEFF to 0xFF255C91
        "violet" -> 0xFFEDE3FA to 0xFF69418A
        "rose" -> 0xFFF9E1E7 to 0xFF953C59
        "orange" -> 0xFFFFE8D3 to 0xFF935015
        "sand" -> 0xFFF4EECF to 0xFF786119
        "gray" -> 0xFFE7EAE5 to 0xFF52604C
        else -> 0xFFDFF8D2 to 0xFF285020
    }
@Composable fun scriptColors(id:String):ScriptColors {
    val pair=scriptColorPair(id)
    return if(isSystemInDarkTheme()) ScriptColors(Color(pair.second).copy(alpha=.35f),Color(pair.first)) else ScriptColors(Color(pair.first),Color(pair.second))
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun AppearancePicker(icon:String,color:String,onIcon:(String)->Unit,onColor:(String)->Unit,onDismiss:()->Unit) {
    val selectedColors=scriptColors(color)
    var query by rememberSaveable {mutableStateOf("")}
    var group by rememberSaveable {mutableStateOf("全部")}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=28.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(selectedColors.background,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center){Glyph(icon,selectedColors.foreground,Modifier.size(28.dp))}
                Text("图标与颜色",fontSize=18.sp,modifier=Modifier.weight(1f).padding(start=16.dp))
                TextButton(onClick=onDismiss){Text("完成")}
            }
            Text("颜色",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                scriptColorOptions.forEach {(id,label)-> val tint=scriptColors(id)
                    Box(Modifier.size(48.dp).semantics {contentDescription=label;selected=color==id}.clickable {onColor(id)},contentAlignment=Alignment.Center) {
                        Box(Modifier.size(34.dp).background(tint.background,CircleShape).then(if(color==id) Modifier.border(2.dp,tint.foreground,CircleShape) else Modifier),contentAlignment=Alignment.Center){if(color==id) Glyph("check",tint.foreground,Modifier.size(18.dp))}
                    }
                }
            }
            Text("图标",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query,{query=it},placeholder={Text("搜索图标",fontSize=14.sp)},leadingIcon={Glyph("search",MaterialTheme.colorScheme.onSurfaceVariant,Modifier.size(18.dp))},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                (listOf("全部")+AppIcons.all.map {it.category}.distinct()).forEach {label->FilterChip(selected=group==label,onClick={group=label},label={Text(label,fontSize=12.sp)})}
            }
            val icons=AppIcons.all.filter {(group=="全部" || it.category==group) && (it.id+it.label+it.category).contains(query.trim(),true)}
            if(icons.isEmpty()) Text("没有匹配的图标",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            icons.chunked(6).forEach {row-> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                row.forEach {entry-> val id=entry.id;val label=entry.label
                    Surface(onClick={onIcon(id)},modifier=Modifier.size(48.dp).semantics {contentDescription=label;selected=icon==id},shape=RoundedCornerShape(14.dp),color=if(icon==id) selectedColors.background else MaterialTheme.colorScheme.surfaceContainerLow,border=if(icon==id) BorderStroke(1.dp,selectedColors.foreground) else null) {
                        Box(contentAlignment=Alignment.Center){Glyph(id,if(icon==id) selectedColors.foreground else MaterialTheme.colorScheme.onSurfaceVariant,Modifier.size(24.dp))}
                    }
                }
                repeat(6-row.size) {Spacer(Modifier.size(48.dp))}
            }}
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CategoryPicker(selected:String,categories:List<ScriptCategory>,onSelect:(String)->Unit,onDismiss:()->Unit) {
    val colors=MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=colors.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("选择分类",fontSize=18.sp,modifier=Modifier.padding(bottom=12.dp))
            (listOf(ScriptCategory("","未分类"))+categories).forEach {entry->
                Surface(onClick={onSelect(entry.id)},shape=RoundedCornerShape(12.dp),color=if(selected==entry.id) colors.primaryContainer else colors.surface) {
                    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Glyph("folder",colors.primary,Modifier.size(20.dp)); Text(entry.name,fontSize=14.sp,modifier=Modifier.weight(1f).padding(horizontal=14.dp))
                        if(selected==entry.id) Glyph("check",colors.primary,Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
