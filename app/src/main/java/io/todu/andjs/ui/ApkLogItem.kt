package io.todu.andjs.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.todu.andjs.runtime.LogEntry

@Composable fun ApkLogItem(entry:LogEntry) {
    val context=LocalContext.current
    Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=MaterialTheme.shapes.medium,
        modifier=Modifier.fillMaxWidth().clickable(onClickLabel="安装 APK") {
            try {
                val uri=Uri.parse(requireNotNull(entry.installUri))
                check(uri.scheme=="content") {"安装文件地址无效"}
                if(!context.packageManager.canRequestPackageInstalls()) {
                    permissionFlow(context) {context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                    Toast.makeText(context,"允许安装后，请返回再次点击安装",Toast.LENGTH_LONG).show()
                } else {
                    context.contentResolver.openAssetFileDescriptor(uri,"r")?.use {} ?: error("安装包已不存在")
                    val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData=android.content.ClipData.newRawUri("APK",uri)
                    permissionFlow(context) {context.startActivity(intent)}
                }
            } catch(e:Exception) {
                val message=when(e) {
                    is android.content.ActivityNotFoundException->"系统未提供安装或安装授权页面"
                    is SecurityException->"系统不允许安装此文件"
                    else->e.message ?: "无法打开安装包"
                }
                Toast.makeText(context,message,Toast.LENGTH_LONG).show()
            }
        }) {
        Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(entry.text,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurface)
            Text("安装",maxLines=1,softWrap=false,fontSize=13.sp,color=MaterialTheme.colorScheme.primary)
        }
    }
}
