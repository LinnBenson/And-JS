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
    "sapphire" to "宝蓝",
    "lake" to "湖蓝",
    "petrol" to "深青",
    "pine" to "松绿",
    "jade" to "碧绿",
    "grape" to "深紫",
    "berry" to "莓红",
    "wine" to "酒红",
    "brick" to "砖红",
    "copper" to "铜橙",
    "ochre" to "赭黄",
    "midnight" to "墨蓝",
)
fun scriptColorPair(id:String):Pair<Long,Long> = when(id) {
    "emerald" -> 0xFF20563E to 0xFFFFFFFF
    "teal" -> 0xFF268272 to 0xFFFFFFFF
    "cyan" -> 0xFF5E797D to 0xFFFFFFFF
    "cobalt" -> 0xFF2455A4 to 0xFFFFFFFF
    "indigo" -> 0xFF34365F to 0xFFFFFFFF
    "lavender" -> 0xFF787183 to 0xFFFFFFFF
    "purple" -> 0xFF7A4295 to 0xFFFFFFFF
    "pink" -> 0xFF886D7A to 0xFFFFFFFF
    "red" -> 0xFFB9433D to 0xFFFFFFFF
    "peach" -> 0xFF847062 to 0xFFFFFFFF
    "amber" -> 0xFF946E22 to 0xFFFFFFFF
    "lemon" -> 0xFF7E7539 to 0xFFFFFFFF
    "lime" -> 0xFF657C34 to 0xFFFFFFFF
    "olive" -> 0xFF59643B to 0xFFFFFFFF
    "brown" -> 0xFF654638 to 0xFFFFFFFF
    "taupe" -> 0xFF7B736B to 0xFFFFFFFF
    "slate" -> 0xFF42586B to 0xFFFFFFFF
    "blue" -> 0xFF45799D to 0xFFFFFFFF
    "violet" -> 0xFF8269A1 to 0xFFFFFFFF
    "rose" -> 0xFFA13F64 to 0xFFFFFFFF
    "orange" -> 0xFFA7642D to 0xFFFFFFFF
    "sand" -> 0xFF7D745C to 0xFFFFFFFF
    "gray" -> 0xFF363C43 to 0xFFFFFFFF
    "sapphire" -> 0xFF345BD2 to 0xFFFFFFFF
    "lake" -> 0xFF087D96 to 0xFFFFFFFF
    "petrol" -> 0xFF164B56 to 0xFFFFFFFF
    "pine" -> 0xFF246747 to 0xFFFFFFFF
    "jade" -> 0xFF19835F to 0xFFFFFFFF
    "grape" -> 0xFF50265F to 0xFFFFFFFF
    "berry" -> 0xFFB52D77 to 0xFFFFFFFF
    "wine" -> 0xFF6D233F to 0xFFFFFFFF
    "brick" -> 0xFF984132 to 0xFFFFFFFF
    "copper" -> 0xFFB45923 to 0xFFFFFFFF
    "ochre" -> 0xFF8D7020 to 0xFFFFFFFF
    "midnight" -> 0xFF202D46 to 0xFFFFFFFF
    else -> 0xFF65796F to 0xFFFFFFFF
}
@Composable fun scriptColors(id:String):ScriptColors {
    val pair=scriptColorPair(id)
    return ScriptColors(Color(pair.first),Color(pair.second))
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
                        Glyph("folder",if(selected==entry.id) colors.onPrimaryContainer else colors.primary,Modifier.size(20.dp)); Text(entry.name,fontSize=14.sp,modifier=Modifier.weight(1f).padding(horizontal=14.dp))
                        if(selected==entry.id) Glyph("check",colors.onPrimaryContainer,Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
