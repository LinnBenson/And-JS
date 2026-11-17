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
    companion object {
        private val lock=Any()
        private val active=mutableSetOf<String>()
        private fun records(context:Context)=File(context.noBackupFilesDir,"http-upload-pending")
        @Suppress("DEPRECATION")
        fun clean(context:Context)=synchronized(lock) {
            if(Build.VERSION.SDK_INT>=29) {
                // IS_PENDING is the durable commit marker, including uploads from older versions.
                val collection=MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                val where="${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                val args=arrayOf(context.packageName,Environment.DIRECTORY_DOWNLOADS+"/")
                val ids=mutableListOf<Long>()
                context.contentResolver.query(collection,arrayOf(MediaStore.MediaColumns._ID),where,args,null)?.use {cursor->
                    while(cursor.moveToNext()) ids.add(cursor.getLong(0))
                } ?: error("无法检查未完成上传")
                ids.forEach {id->
                    val item=android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,id)
                    if(item.toString() !in active) context.contentResolver.delete(item,where,args)
                }
            }
            val directory=records(context)
            if(directory.exists()) {
                val entries=directory.listFiles() ?: error("无法读取未完成上传记录")
                entries.filter {it.name.matches(Regex("[0-9a-f-]{36}"))}.forEach {record->
                    val pending=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),".andjs-upload-${record.name}.part")
                    if(pending.absolutePath !in active) {
                        check(!pending.exists() || pending.delete()) {"无法清理未完成上传"}
                        check(record.delete()) {"无法清理上传记录"}
                    }
                }
            }
        }
    }
    private var record:File?=null
    private var uri:Uri?=null
    private var file:File?=null
    private var committed=false
    private var stream:OutputStream?=null
    var name=requestedName
        private set
    val output:OutputStream get()=checkNotNull(stream)
    init {
        synchronized(lock) { try {
            if(Build.VERSION.SDK_INT>=29) {
                val values=ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                    put(MediaStore.MediaColumns.MIME_TYPE,android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING,1)
                }
                val created=checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)) {"无法创建下载文件"}
                uri=created
                active.add(created.toString())
                stream=checkNotNull(context.contentResolver.openOutputStream(created,"w")) {"无法写入下载文件"}
                context.contentResolver.query(created,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) name=it.getString(0)
                }
            } else {
                check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED) {"Android 8–9 上传文件需先授予存储权限"}
                @Suppress("DEPRECATION") val dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                check(dir.isDirectory || dir.mkdirs()) {"无法创建下载目录"}
                val id=java.util.UUID.randomUUID().toString()
                val journal=records(context)
                check(journal.isDirectory || journal.mkdirs()) {"无法记录未完成上传"}
                record=File(journal,id).also {entry->
                    java.io.FileOutputStream(entry).use {it.write(1);it.fd.sync()}
                }
                file=File(dir,".andjs-upload-$id.part")
                active.add(file!!.absolutePath)
                stream=file!!.outputStream()
            }
        } catch(e:Exception) {close();throw e} }
    }
    fun commit() = synchronized(lock) {
        output.flush();output.close();stream=null
        uri?.let {
            check(context.contentResolver.update(it,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)==1) {"无法保存下载文件"}
        }
        file?.let {pending->
            var suffix=0
            while(true) {
                val dot=name.lastIndexOf('.').takeIf {it>0} ?: name.length
                val candidateName=if(suffix==0) name else name.substring(0,dot)+" ($suffix)"+name.substring(dot)
                val destination=File(pending.parentFile,candidateName)
                try {
                    java.nio.file.Files.move(pending.toPath(),destination.toPath())
                    name=candidateName
                    break
                } catch(e:java.nio.file.FileAlreadyExistsException) {
                    check(++suffix<=1000) {"同名文件过多"}
                }
            }
        }
        committed=true
        record?.delete()
        // MediaStore may choose the collision-free name only when publishing the pending row.
        uri?.let {saved->
            context.contentResolver.query(saved,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) name=it.getString(0)
            }
        }
    }
    override fun close() { synchronized(lock) {
        runCatching {stream?.close()};stream=null
        if(!committed) {
            uri?.let {runCatching {context.contentResolver.delete(it,"${MediaStore.MediaColumns.IS_PENDING}=1",null)}}
            file?.let {if(!it.exists() || it.delete()) record?.delete()}
        }
        uri?.let {active.remove(it.toString())}
        file?.let {active.remove(it.absolutePath)}
    } }
}
