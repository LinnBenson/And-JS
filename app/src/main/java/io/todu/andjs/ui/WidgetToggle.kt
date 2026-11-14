package io.todu.andjs.ui
import androidx.compose.runtime.Composable
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
@Composable fun WidgetToggle(checked:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true) {
    IconToggleButton(checked=checked,onCheckedChange=onChange,enabled=enabled,colors=IconButtonDefaults.iconToggleButtonColors(containerColor=androidx.compose.ui.graphics.Color.Transparent,checkedContainerColor=androidx.compose.ui.graphics.Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.45f),checkedContentColor=MaterialTheme.colorScheme.onSurfaceVariant)) {
        Glyph(if(checked) "layout-grid-filled" else "layout-grid",LocalContentColor.current,Modifier.size(20.dp),"在小部件显示")
    }
}
