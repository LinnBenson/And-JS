package io.todu.andjs.runtime

import java.io.Closeable
import java.util.concurrent.CompletableFuture

internal data class HttpServerOptions(val port:Int=8080,val token:String,val maxFileSizeMB:Int=100,val chat:Boolean=false,val maxImageSizeMB:Int=2,val name:String="And JS",val editor:((String,org.json.JSONObject?)->org.json.JSONObject)?=null)
data class UploadProgress(val id:String,val name:String,val receivedBytes:Long,val totalBytes:Long,val status:String="uploading",val error:String?=null,val kind:String="file",val installUri:String?=null) {
    fun values():Map<String,Any?> = mapOf("id" to id,"name" to name,"receivedBytes" to receivedBytes,"totalBytes" to totalBytes,
        "percent" to if(status=="completed") 100 else if(totalBytes==0L) 0 else (receivedBytes*100/totalBytes).toInt().coerceAtMost(99),"status" to status,"error" to error,"kind" to kind)
}
sealed class HttpServerEvent {
    data object Wake:HttpServerEvent()
    class Message(val value:Map<String,Any?>):HttpServerEvent()
    class Text(val text:String,val reply:CompletableFuture<Boolean> = CompletableFuture()):HttpServerEvent()
    class Image(val base64:String,val reply:CompletableFuture<String?> = CompletableFuture()):HttpServerEvent()
    class Progress(val value:UploadProgress):HttpServerEvent()
}
interface HttpServerSession:Closeable {
    val url:String
    val token:String
    val failure:String?
    val isClosed:Boolean
    val sessionId:String? get()=null
    fun sendSystem(text:String):Any? = error("当前服务不支持系统消息")
    val progress:UploadProgress?
    fun poll(timeoutMs:Long):HttpServerEvent?
    fun awaitClosed()
}
