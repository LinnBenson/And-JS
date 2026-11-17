package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.domain.ScriptParameter
import io.todu.andjs.runtime.ParameterValues

/** Shared compact list. Actions retain original indices even while filtered. */
@Composable
fun ParameterList(
    parameters: List<ScriptParameter>,
    query: String,
    onQuery: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    showAdd: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    var deleting by remember { mutableStateOf<Int?>(null) }
    val matches = parameters.withIndex().filter {
        it.value.key.contains(query, true) || it.value.type.contains(query, true)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("搜索键或类型", fontSize = 14.sp) },
                leadingIcon = { Glyph("search", colors.onSurfaceVariant, Modifier.size(19.dp)) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { onQuery("") }) { Text("×", fontSize = 22.sp) }
                }) else null,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = colors.surfaceVariant,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            if (showAdd) IconButton(onClick = onAdd, enabled = enabled) {
                Glyph("plus", colors.primary, Modifier.size(23.dp), "添加参数")
            }
        }
        if (matches.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "暂无变量" else "没有匹配的变量", color = colors.onSurfaceVariant, fontSize = 14.sp)
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = bottomPadding)) {
            items(matches, key = { it.index }) { (index, parameter) ->
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surface) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(parameter.key, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(parameter.value.ifEmpty { "空" }, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                        Text(parameter.type, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        IconButton(onClick = { onEdit(index) }, enabled = enabled) {
                            Glyph("pencil", colors.onSurfaceVariant, Modifier.size(19.dp), "编辑${parameter.key}")
                        }
                        IconButton(onClick = { deleting = index }, enabled = enabled) {
                            Glyph("trash-2", colors.onSurfaceVariant, Modifier.size(19.dp), "删除${parameter.key}")
                        }
                    }
                }
            }
        }
    }
    deleting?.let { index ->
        parameters.getOrNull(index)?.let { parameter ->
            AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除变量？") }, text = { Text(parameter.key) },
                confirmButton = { TextButton(onClick = { deleting = null; onDelete(index) }) { Text("删除") } },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
        }
    }
}

/** Full secondary page; no partially edited values are committed to the list. */
@Composable
fun ParameterEditScreen(
    initial: ScriptParameter,
    existing: List<ScriptParameter>,
    editingIndex: Int,
    onBack: () -> Unit,
    onSave: (ScriptParameter) -> Unit,
    saving: Boolean = false,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var key by rememberSaveable { mutableStateOf(initial.key) }
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var value by rememberSaveable { mutableStateOf(initial.value) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val dirty = key != initial.key || type != initial.type || value != initial.value
    val close = { if (!saving) { if (dirty) discard = true else onBack() } }
    val colors = MaterialTheme.colorScheme
    BackHandler(onBack = close)
    Scaffold(containerColor = colors.background, modifier = Modifier.imePadding(), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        Row(Modifier.statusBarsPadding().fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, enabled = !saving) { Glyph("back", colors.onSurface, Modifier.size(22.dp), "返回") }
            Text(if (editingIndex < 0) "新建变量" else "编辑变量", fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(enabled = !saving, onClick = {
                val candidate = ScriptParameter(key.trim(), type, value)
                error = when {
                    candidate.key.isEmpty() -> "键不能为空"
                    existing.withIndex().any { it.index != editingIndex && it.value.key == candidate.key } -> "键已存在"
                    else -> ParameterValues.error(listOf(candidate))
                }
                if (error == null) onSave(candidate)
            }) { Text(if (saving) "保存中" else "保存") }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(key, { key = it; error = null }, label = { Text("键") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("boolean", "string", "number", "array").forEach { option ->
                    FilterChip(selected = type == option, enabled = !saving, onClick = {
                        if (type != option) {
                            type = option
                            value = when (option) { "boolean" -> "false"; "number" -> "0"; "array" -> "[]"; else -> "" }
                            error = null
                        }
                    }, label = { Text(option, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
                }
            }
            if (type == "boolean") Surface(shape = RoundedCornerShape(14.dp), color = colors.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                    Switch(checked = value == "true", enabled = !saving, onCheckedChange = { value = it.toString() })
                }
            } else OutlinedTextField(value, { value = it; error = null }, enabled = !saving,
                label = { Text(if (type == "array") "JSON 数组" else "值") },
                minLines = if (type == "array") 5 else 2, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
            error?.let { Text(it, color = colors.error, fontSize = 13.sp) }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃修改？") },
        confirmButton = { TextButton(onClick = onBack) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}
