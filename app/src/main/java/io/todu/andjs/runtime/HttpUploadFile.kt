package io.todu.andjs.runtime

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/** Pending downloads never become visible as completed files after an interrupted upload. */
internal class HttpUploadFile(private val context:Context,requestedName:String):Closeable {
    private var uri:Uri?=null
    private var file:File?=null
    private var committed=false
    private var stream:OutputStream?=null
    var name=requestedName
        private set
    val output:OutputStream get()=checkNotNull(stream)
    init {
        try {
            if(Build.VERSION.SDK_INT>=29) {
                val values=ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                    put(MediaStore.MediaColumns.MIME_TYPE,android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING,1)
                }
                val created=checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)) {"无法创建下载文件"}
                uri=created
                stream=checkNotNull(context.contentResolver.openOutputStream(created,"w")) {"无法写入下载文件"}
                context.contentResolver.query(created,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) name=it.getString(0)
                }
            } else {
                check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED) {"Android 8–9 上传文件需先授予存储权限"}
                @Suppress("DEPRECATION") val dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                check(dir.isDirectory || dir.mkdirs()) {"无法创建下载目录"}
                // Reserve a unique file without overwriting any existing download.
                var candidate=File(dir,name)
                var suffix=1
                while(!candidate.createNewFile()) {
                    check(suffix<=1000) {"同名文件过多"}
                    val dot=name.lastIndexOf('.').takeIf {it>0} ?: name.length
                    candidate=File(dir,name.substring(0,dot)+" (${suffix++})"+name.substring(dot))
                }
                file=candidate;name=candidate.name;stream=candidate.outputStream()
            }
        } catch(e:Exception) {close();throw e}
    }
    fun commit() {
        output.flush();output.close();stream=null
        uri?.let {
            check(context.contentResolver.update(it,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1) {"无法保存下载文件"}
        }
        committed=true
        // MediaStore may choose the collision-free name only when publishing the pending row.
        uri?.let {saved->
            context.contentResolver.query(saved,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) name=it.getString(0)
            }
        }
    }
    override fun close() {
        runCatching {stream?.close()};stream=null
        if(!committed) {uri?.let {runCatching {context.contentResolver.delete(it,null,null)}};file?.delete()}
    }
}
