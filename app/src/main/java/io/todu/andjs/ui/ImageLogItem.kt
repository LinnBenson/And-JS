package io.todu.andjs.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.todu.andjs.runtime.LogEntry
import java.util.Base64

@Composable fun ImageLogItem(entry:LogEntry,onPreview:(androidx.compose.ui.graphics.ImageBitmap)->Unit) {
    val bitmap=remember(entry.text) {
        runCatching {val bytes=Base64.getDecoder().decode(entry.text);BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()}.getOrNull()
    }
    val density=androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(8.dp),contentAlignment=Alignment.Center) {
        if(bitmap==null) Text("图片无法显示")
        else {
            val width=entry.imageWidthPercent?.let {maxWidth*(it/100f)}
                ?: minOf(maxWidth,entry.imageWidth.dp,with(density) {bitmap.width.toDp()},320.dp*entry.imageRatio)
            Image(bitmap,contentDescription="图片日志",contentScale=ContentScale.Fit,modifier=Modifier.width(width).aspectRatio(entry.imageRatio).clickable(onClickLabel="预览图片") {onPreview(bitmap)})
        }
    }
}
