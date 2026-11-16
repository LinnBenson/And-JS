package io.todu.andjs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable internal fun WatchBackButton(label:String="返回",onClick:()->Unit) {
    IconButton(onClick=onClick,modifier=Modifier.size(48.dp)) {
        Box(Modifier.size(32.dp).background(Color(0xFF151B21),CircleShape),contentAlignment=Alignment.Center) {
            Glyph("x",Color(0xFF8FAABE),Modifier.size(20.dp),label)
        }
    }
}
