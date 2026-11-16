package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import io.todu.andjs.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.domain.ScriptParameter

@Composable
fun GlobalEditorScreen(
    initial: List<ScriptParameter>,
    onNavigate: (Int) -> Unit,
    onSave: (List<ScriptParameter>, (Boolean) -> Unit) -> Unit,
    message: String?,
    onDismissMessage: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); onDismissMessage() } }
    editing?.let { index ->
        ParameterEditScreen(initial.getOrNull(index) ?: ScriptParameter(""), initial, index,
            onBack = { editing = null }, saving = saving, snackbar = snack,
            onSave = { parameter ->
                val updated = initial.toMutableList().apply { if (index < 0) add(parameter) else set(index, parameter) }
                saving = true
                onSave(updated) { success -> saving = false; if (success) editing = null }
            })
        return
    }
    BackHandler { if (!saving) onNavigate(0) }
    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snack) },
        bottomBar = { AppNavigation(1) { if (!saving && it != 1) onNavigate(it) } }, topBar = {
            MainPageHeader("全局变量",Modifier.statusBarsPadding().padding(horizontal=20.dp)) {
                IconButton(onClick = { editing = -1 }, enabled = !saving, modifier = Modifier.size(48.dp)) {
                    Glyph("plus", colors.primary, Modifier.size(23.dp), "添加全局变量")
                }
            }
        }) { padding ->
        ParameterList(initial, query, { query = it }, onAdd = { editing = -1 }, onEdit = { editing = it },
            onDelete = { index ->
                saving = true
                onSave(initial.filterIndexed { i, _ -> i != index }) { saving = false }
            }, modifier = Modifier.padding(top=padding.calculateTopPadding()).padding(horizontal = 20.dp).fillMaxSize(), bottomPadding=padding.calculateBottomPadding()+16.dp, showAdd = false, enabled = !saving)
    }
}
