package io.todu.andjs.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object ScriptExtras {
    fun qr(text:String):String {
        require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size<=2000) {"二维码内容须为 1–2000 个 UTF-8 字节"}
        val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,320,320,mapOf(EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.MARGIN to 4))
        val bitmap=Bitmap.createBitmap(matrix.width,matrix.height,Bitmap.Config.ARGB_8888)
        try {
            val pixels=IntArray(matrix.width*matrix.height) {i->if(matrix[i%matrix.width,i/matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE}
            bitmap.setPixels(pixels,0,matrix.width,0,0,matrix.width,matrix.height)
            val bytes=ByteArrayOutputStream().use {out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()}
            return "data:image/png;base64,"+android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        } finally {bitmap.recycle()}
    }
    fun clipboard(context:Context,method:String,args:JSONArray):Any? = runBlocking(Dispatchers.Main) {
        val manager=context.getSystemService(ClipboardManager::class.java)
        when(method) {
            "get"->manager.primaryClip?.takeIf {it.itemCount>0}?.getItemAt(0)?.coerceToText(context)?.toString()
            "set"->{val text=args.get(0);require(text is String && text.length<=1_000_000) {"剪贴板内容须为不超过 100 万字符的字符串"};manager.setPrimaryClip(ClipData.newPlainText("And JS",text));true}
            else->error("未知剪贴板方法")
        }
    }
    fun openApp(context:Context,packageName:String):Boolean {
        require(packageName.length in 1..255 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))) {"请提供有效的应用包名"}
        val intent=context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("应用未安装、已停用或没有可打开的入口：$packageName")
        launch(context,intent)
        return true
    }
    fun openUrl(context:Context,url:String):Boolean {
        require(url.isNotBlank() && url.length<=8192) {"链接不能为空或过长"}
        val uri=Uri.parse(url)
        require(!uri.scheme.isNullOrBlank() && uri.scheme!!.lowercase() !in listOf("file","content","javascript","intent")) {"请提供网页、geo: 或应用支持的链接，不支持文件及 intent 链接"}
        launch(context,Intent(Intent.ACTION_VIEW,uri));return true
    }
    fun cleanShares(context:Context) {
        File(context.cacheDir,"script-shares").listFiles()?.filter {it.isFile && System.currentTimeMillis()-it.lastModified()>86_400_000}?.forEach {it.delete()}
    }
    fun share(context:Context,value:String,resolve:(String)->File):Boolean {
        val intent=Intent(Intent.ACTION_SEND)
        if(value.startsWith("app:/") || value.startsWith("shared:/")) {
            val source=resolve(value)
            require(source.isFile && source.length()<=32L*1024*1024) {"分享文件不存在或超过 32MB"}
            cleanShares(context)
            val root=File(context.cacheDir,"script-shares").apply {mkdirs()}
            require(root.listFiles().orEmpty().sumOf {it.length()}+source.length()<=64L*1024*1024) {"分享临时文件总量已达 64MB，请稍后重试"}
            val copy=File(root,UUID.randomUUID().toString()+"-"+source.name.takeLast(100))
            try {
                source.copyTo(copy)
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.scriptfiles",copy)
                intent.type=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream"
                intent.putExtra(Intent.EXTRA_STREAM,uri)
                intent.clipData=ClipData.newRawUri(source.name,uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launch(context,Intent.createChooser(intent,null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch(e:Exception) {copy.delete();throw e}
        } else {
            require(value.length<=1_000_000) {"分享文本过长"}
            intent.type="text/plain";intent.putExtra(Intent.EXTRA_TEXT,value)
            launch(context,Intent.createChooser(intent,null))
        }
        return true
    }
    private fun launch(context:Context,intent:Intent)=runBlocking(Dispatchers.Main) {
        val app=context.applicationContext as? io.todu.andjs.AndJsApplication
        app?.externalFlow=true
        try {context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        catch(e:android.content.ActivityNotFoundException) {app?.externalFlow=false;error("设备上没有可以处理此操作的应用")}
        catch(e:Exception) {app?.externalFlow=false;throw e}
    }
}
