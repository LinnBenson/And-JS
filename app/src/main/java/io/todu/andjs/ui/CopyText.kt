package io.todu.andjs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun copyTextAction(text:String,label:String):()->Unit {
    val context=LocalContext.current
    return {
        val copied=runCatching {
            checkNotNull(context.getSystemService(ClipboardManager::class.java))
                .setPrimaryClip(ClipData.newPlainText(label,text))
        }.isSuccess
        Toast.makeText(context,if(copied) "已复制" else "复制失败，请重试",Toast.LENGTH_SHORT).show()
    }
}
