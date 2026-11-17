package io.todu.andjs.runtime

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Session-only disk storage. Nothing is restored after process death. */
internal class HttpChatStore(root:File,private val capacity:Long) {
    companion object {
        fun recover(root:File) {if(root.exists()) check(root.deleteRecursively()) {"无法清理上次聊天数据"}}
    }
    val sessionId=UUID.randomUUID().toString()
    private val directory:File
    private var nextId=1L
    private var storedBytes=0L
    private var closed=false
    private val clients=mutableMapOf<String,String>()
    private val authorized=mutableSetOf<String>()
    init {recover(root);directory=File(root,sessionId);check(directory.mkdirs()) {"无法创建聊天目录"}}
    @Synchronized fun join(existing:String?):Pair<String,String> {
        check(!closed) {"聊天已结束"}
        if(existing!=null) clients[existing]?.let {authorized.add(existing);return existing to it}
        val secret=UUID.randomUUID().toString()+UUID.randomUUID().toString()
        val id="U-"+UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        clients[secret]=id;authorized.add(secret)
        return secret to id
    }
    @Synchronized fun sender(secret:String?):String?=if(closed || secret !in authorized) null else clients[secret]
    @Synchronized fun leave(secret:String?) {authorized.remove(secret)}
    @Synchronized fun reserve(size:Long) {
        check(!closed) {"聊天已结束"}
        check(nextId<=10000) {"本次聊天记录已满，请重新启动聊天"}
        check(storedBytes+size<=capacity) {"本次聊天空间已满，请重新启动聊天"}
        check(directory.usableSpace>size+8*1024*1024) {"设备存储空间不足"}
    }
    fun temporary():File=File.createTempFile("upload-", ".part",directory)
    @Synchronized fun append(sender:String,type:String,text:String?=null,name:String?=null,size:Long=0,temp:File?=null,mime:String?=null):Map<String,Any?> {
        reserve(size+(text?.toByteArray(Charsets.UTF_8)?.size ?: 0))
        val id=nextId
        val attachment=File(directory,"$id.bin")
        val metadata=File(directory,"$id.json")
        try {
            if(temp!=null) check(temp.renameTo(attachment)) {"附件保存失败"}
            val message=linkedMapOf<String,Any?>("id" to id,"senderId" to sender,"type" to type,"time" to System.currentTimeMillis(),
                "text" to text,"name" to name,"size" to if(type=="text") null else size,"system" to (sender=="system"),"mime" to mime)
            metadata.writeText(JSONObject(message).toString())
            storedBytes+=size+(text?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            nextId++
            return message
        } catch(e:Exception) {attachment.delete();metadata.delete();throw e}
    }
    @Synchronized fun messages(after:Long):JSONObject {
        check(!closed) {"聊天已结束"}
        val result=JSONArray()
        var cursor=after.coerceAtMost(nextId-1)
        while(cursor+1<nextId && result.length()<50) {cursor++;result.put(JSONObject(File(directory,"$cursor.json").readText()))}
        return JSONObject().put("messages",result).put("cursor",cursor).put("more",cursor+1<nextId).put("sessionId",sessionId)
    }
    @Synchronized fun attachment(id:Long):Pair<File,JSONObject>? {
        if(closed || id<1 || id>=nextId) return null
        val file=File(directory,"$id.bin")
        if(!file.isFile) return null
        return file to JSONObject(File(directory,"$id.json").readText())
    }
    @Synchronized fun close() {closed=true;clients.clear();authorized.clear();directory.deleteRecursively()}
}
