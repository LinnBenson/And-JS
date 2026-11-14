package io.todu.andjs.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import org.json.JSONArray

class ScriptFiles(private val appRoot:File,private val sharedRoot:File,private val lowMemory:Boolean=false,private val hasShared:()->Boolean) {
    private fun resolve(path:String):File {
        val shared=path.startsWith("shared:/")
        require(!shared || hasShared()) {"请先在设置中授权共享文件访问"}
        val root=if(shared) sharedRoot else appRoot
        val relative=when {shared->path.removePrefix("shared:/");path.startsWith("app:/")->path.removePrefix("app:/");else->path}
        require(!relative.startsWith("/") && !relative.contains('\u0000')) {"请使用 app:/ 或 shared:/ 路径"}
        val target=File(root,relative).canonicalFile
        require(target==root.canonicalFile || target.path.startsWith(root.canonicalPath+File.separator)) {"路径超出允许目录"}
        // Runtime-managed documents and signing/identity state are not exposed through file APIs.
        if(!shared) {
            val allowed=root.canonicalFile
            val requested=File(allowed,relative).canonicalFile
            require(requested==allowed || requested.path.startsWith(allowed.path+File.separator)) {"路径超出文件目录"}
            return requested
        }
        return target
    }
    private fun describe(file:File,path:String):Map<String,Any> = mapOf("name" to file.name,"path" to path,"exists" to file.exists(),"isDirectory" to file.isDirectory,"size" to if(file.isFile) file.length() else 0L,"modifiedAt" to file.lastModified())

    fun call(method:String,args:JSONArray):Any? {
        fun string(index:Int,default:String?=null):String {
            if(index>=args.length() && default!=null) return default
            val value=args.get(index);require(value is String) {"参数必须为字符串"};return value
        }

                val path=string(0);val file=resolve(path)
                return when(method) {
                    "exists"->file.exists()
                    "info"->describe(file,path)
                    "list"->{require(file.isDirectory) {"目录不存在"};val children=file.listFiles() ?: error("无法读取目录");require(children.size<=10000) {"目录条目过多"};children.sortedBy {it.name}.map {describe(it,path.trimEnd('/')+"/"+it.name)}}
                    "mkdir"->file.isDirectory || file.mkdirs()
                    "read"->{require(file.isFile && file.length()<=(if(lowMemory) 2L else 16L)*1024*1024) {"文件不存在或超过设备读取上限"};when(string(1,"utf8")) {"utf8"->file.readText(Charsets.UTF_8);"base64"->Base64.getEncoder().encodeToString(file.readBytes());else->throw IllegalArgumentException("编码须为 utf8 或 base64")}}
                    "write","append"->{
                        val data=string(1);require(data.length<=(if(lowMemory) 2 else 24)*1024*1024) {"内容过大"}
                        val bytes=when(string(2,"utf8")) {"utf8"->data.toByteArray(Charsets.UTF_8);"base64"->Base64.getDecoder().decode(data);else->throw IllegalArgumentException("编码须为 utf8 或 base64")}
                        require(file.name.isNotEmpty() && file!=resolve(if(path.startsWith("shared:/")) "shared:/" else "app:/")) {"不能写入根目录"}
                        file.parentFile?.mkdirs()
                        if(method=="append") file.appendBytes(bytes) else {
                            val temp=File.createTempFile("write-",".tmp",file.parentFile)
                            try {temp.writeBytes(bytes);Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)} finally {temp.delete()}
                        };true
                    }
                    "delete"->{require(file!=resolve(if(path.startsWith("shared:/")) "shared:/" else "app:/")) {"不能删除根目录"};if(!file.exists()) false else if(file.isDirectory && args.optBoolean(1,false)) run {
                        Files.walkFileTree(file.toPath(),object:java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                            override fun visitFile(path:java.nio.file.Path,attrs:java.nio.file.attribute.BasicFileAttributes):java.nio.file.FileVisitResult {Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE}
                            override fun postVisitDirectory(path:java.nio.file.Path,error:java.io.IOException?):java.nio.file.FileVisitResult {if(error!=null) throw error;Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE}
                        });true
                    } else file.delete()}
                    "copy","move"->{
                        require(file.isFile) {"只支持文件"};val target=resolve(string(1));require(file!=target && (!target.exists() || args.optBoolean(2,false))) {"目标已存在或与源相同"};target.parentFile?.mkdirs()
                        if(method=="copy") file.copyTo(target,args.optBoolean(2,false)) else Files.move(file.toPath(),target.toPath(),*if(args.optBoolean(2,false)) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray())
                        true
                    }
                    else->error("未知方法")
                }

    }
}
