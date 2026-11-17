package io.todu.andjs.runtime

import java.io.Closeable
import java.util.concurrent.CompletableFuture

internal data class HttpServerOptions(val port:Int=8080,val token:String,val maxFileSizeMB:Int=100)
data class UploadProgress(val id:String,val name:String,val receivedBytes:Long,val totalBytes:Long,val status:String="uploading",val error:String?=null) {
    fun values():Map<String,Any?> = mapOf("id" to id,"name" to name,"receivedBytes" to receivedBytes,"totalBytes" to totalBytes,
        "percent" to if(status=="completed") 100 else if(totalBytes==0L) 0 else (receivedBytes*100/totalBytes).toInt().coerceAtMost(99),"status" to status,"error" to error)
}
sealed class HttpServerEvent {
    data object Wake:HttpServerEvent()
    class Text(val text:String,val reply:CompletableFuture<Boolean> = CompletableFuture()):HttpServerEvent()
    class Progress(val value:UploadProgress):HttpServerEvent()
}
interface HttpServerSession:Closeable {
    val url:String
    val token:String
    val failure:String?
    val isClosed:Boolean
    val progress:UploadProgress?
    fun poll(timeoutMs:Long):HttpServerEvent?
    fun awaitClosed()
}
