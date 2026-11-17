package io.todu.andjs.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** A small, connection-close HTTP/1.x endpoint; no request body is buffered except bounded text. */
internal class AndroidHttpServer private constructor(
    private val context:Context,private val options:HttpServerOptions,private val control:RunControl,
    private val manager:ConnectivityManager,private val network:Network,private val address:Inet4Address,private val lease:Any
):HttpServerSession {
    companion object {
        private val owner=AtomicReference<Any?>()
        fun open(context:Context,options:HttpServerOptions,control:RunControl):AndroidHttpServer {
            val lease=Any()
            check(owner.compareAndSet(null,lease)) {"已有 HTTP 服务正在运行"}
            var server:AndroidHttpServer?=null
            try {
                val manager=context.getSystemService(ConnectivityManager::class.java)
                val wifi=manager.allNetworks.firstOrNull {manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}
                    ?: error("请先连接 Wi-Fi")
                val address=manager.getLinkProperties(wifi)?.linkAddresses?.map {it.address}?.filterIsInstance<Inet4Address>()?.firstOrNull {!it.isLoopbackAddress && !it.isLinkLocalAddress}
                    ?: error("Wi-Fi 尚未取得 IPv4 地址")
                server=AndroidHttpServer(context,options,control,manager,wifi,address,lease)
                server.start()
                return server
            } catch(e:Exception) {
                server?.close();server?.awaitClosed();owner.compareAndSet(lease,null)
                throw e
            }
        }
    }
    private val closed=AtomicBoolean(false)
    private val listener=ServerSocket()
    private val sockets=ConcurrentHashMap.newKeySet<Socket>()
    private val events=ArrayBlockingQueue<HttpServerEvent>(8)
    private val uploading=AtomicBoolean(false)
    private class Transfer(val id:String,val name:String,val total:Long) {
        @Volatile var received=0L
        fun snapshot()=UploadProgress(id,name,received,total)
    }
    private val activeProgress=AtomicReference<Transfer?>()
    private val workers=object:ThreadPoolExecutor(0,2,15,TimeUnit.SECONDS,SynchronousQueue(),{task->Thread(task,"andjs-http-client").apply {isDaemon=true}}) {
        override fun terminated() {owner.compareAndSet(lease,null)}
    }
    private var acceptThread:Thread?=null
    private var unregisterStop:(()->Unit)?=null
    private var registered=false
    private val lifecycleLock=Any()
    @Volatile override var failure:String?=null
        private set
    override val isClosed:Boolean get()=closed.get()
    override val progress:UploadProgress? get()=activeProgress.get()?.snapshot()
    override val token:String get()=options.token
    override val url:String get()="http://${address.hostAddress}:${options.port}"
    private val page by lazy {context.assets.open("http-server.html").use {it.readBytes()}}
    private val networkCallback=object:ConnectivityManager.NetworkCallback() {
        override fun onLost(n:Network) {if(n==network) fail("Wi-Fi 已断开，HTTP 服务已关闭")}
        override fun onLinkPropertiesChanged(n:Network,p:LinkProperties) {
            if(n==network && p.linkAddresses.none {it.address==address}) fail("Wi-Fi 地址已变化，请重新启动 HTTP 服务")
        }
    }
    private fun start() {
        listener.reuseAddress=true
        try {listener.bind(InetSocketAddress(address,options.port),4)} catch(e:java.net.BindException) {error("端口 ${options.port} 已被占用或无法监听")}
        synchronized(lifecycleLock) {
            manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),networkCallback)
            registered=true
        }
        if(manager.getLinkProperties(network)?.linkAddresses?.none {it.address==address}!=false) fail("Wi-Fi 已断开")
        unregisterStop=control.registerStop {close()}
        if(closed.get()) {unregisterStop?.invoke();unregisterStop=null;return}
        acceptThread=Thread({
            try {
                while(!closed.get()) {
                    val socket=listener.accept()
                    sockets.add(socket)
                    if(closed.get()) {sockets.remove(socket);socket.close();break}
                    try {workers.execute {socket.use {serve(it)};sockets.remove(socket)}}
                    catch(_:java.util.concurrent.RejectedExecutionException) {sockets.remove(socket);socket.close()}
                }
            } catch(e:Exception) {if(!closed.get()) fail("HTTP 服务连接失败：${e.message ?: "未知错误"}")}
        },"andjs-http-accept").apply {isDaemon=true;start()}
    }
    private fun fail(message:String) {failure=message;close()}
    override fun poll(timeoutMs:Long):HttpServerEvent?=if(timeoutMs==0L) events.take() else events.poll(timeoutMs,TimeUnit.MILLISECONDS)
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        runCatching {listener.close()}
        sockets.forEach {runCatching {it.close()}}
        workers.shutdownNow()
        synchronized(lifecycleLock) {if(registered) {runCatching {manager.unregisterNetworkCallback(networkCallback)};registered=false}}
        unregisterStop?.invoke();unregisterStop=null
        events.forEach {if(it is HttpServerEvent.Text) it.reply.complete(false)}
        events.clear()
        events.offer(HttpServerEvent.Wake)
    }
    override fun awaitClosed() {
        acceptThread?.join(1500)
        workers.awaitTermination(2000,TimeUnit.MILLISECONDS)
        // The executor releases the process-wide slot only after file cleanup completes.
    }
    private class RequestError(val status:Int,override val message:String):Exception(message)
    private fun reject(status:Int,message:String):Nothing=throw RequestError(status,message)
    private fun respond(socket:Socket,status:Int,bytes:ByteArray,type:String="application/json; charset=utf-8") {
        val reason=when(status) {200->"OK";400->"Bad Request";401->"Unauthorized";403->"Forbidden";404->"Not Found";408->"Request Timeout";409->"Conflict";411->"Length Required";413->"Payload Too Large";429->"Too Many Requests";503->"Service Unavailable";else->"Internal Server Error"}
        val headers="HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'none'; frame-ancestors 'none'; form-action 'self'\r\n\r\n"
        socket.getOutputStream().apply {write(headers.toByteArray(Charsets.US_ASCII));write(bytes);flush()}
    }
    private fun json(socket:Socket,status:Int,message:String,name:String?=null) {
        val value=JSONObject().put("message",message).put("ok",status==200)
        name?.let {value.put("name",it)}
        respond(socket,status,value.toString().toByteArray(Charsets.UTF_8))
    }
    private fun serve(socket:Socket) {
        try {
            socket.soTimeout=5000
            val input=BufferedInputStream(socket.getInputStream(),8192)
            val deadline=System.nanoTime()+5_000_000_000L
            var headerBytes=0
            fun line():String {
                val result=StringBuilder()
                while(true) {
                    val left=(deadline-System.nanoTime())/1_000_000
                    if(left<=0) reject(408,"请求头超时")
                    socket.soTimeout=left.coerceAtMost(5000).coerceAtLeast(1).toInt()
                    val c=input.read()
                    if(c<0) reject(400,"请求不完整")
                    if(++headerBytes>8192) reject(413,"请求头过大")
                    if(c==10) {
                        if(result.isEmpty() || result.last()!='\r') reject(400,"请求格式错误")
                        return result.dropLast(1).toString()
                    }
                    if(c!=13 && c !in 32..126) reject(400,"请求头包含无效字符")
                    result.append(c.toChar())
                }
            }
            val first=line().split(' ')
            if(first.size!=3 || first[2] !in listOf("HTTP/1.1","HTTP/1.0")) reject(400,"请求格式错误")
            val (method,target)=first
            val headers=mutableMapOf<String,String>()
            while(true) {
                val line=line();if(line.isEmpty()) break
                if(headers.size>=32) reject(413,"请求头过多")
                val colon=line.indexOf(':');if(colon<=0) reject(400,"请求头错误")
                val key=line.substring(0,colon).lowercase(java.util.Locale.ROOT)
                if(!key.matches(Regex("[a-z0-9-]+")) || headers.put(key,line.substring(colon+1).trim())!=null) reject(400,"重复或无效请求头")
            }
            if(headers.containsKey("transfer-encoding")) reject(400,"不支持分块上传")
            if(method=="GET" && target=="/") {respond(socket,200,page,"text/html; charset=utf-8");return}
            if(method!="POST" || (target!="/text" && !target.startsWith("/upload?name="))) reject(404,"找不到页面")
            if(headers["origin"]?.let {it!="http://${headers["host"]}"}==true) reject(403,"请从服务网页发送")
            val supplied=headers["x-andjs-token"].orEmpty().toByteArray(Charsets.UTF_8)
            if(!MessageDigest.isEqual(supplied,token.toByteArray(Charsets.UTF_8))) reject(401,"访问码不正确")
            val size=headers["content-length"]?.takeIf {it.isNotEmpty() && it.all {c->c in '0'..'9'}}?.toLongOrNull() ?: reject(411,"缺少有效文件大小")
            socket.soTimeout=15000
            if(target=="/text") {
                if(size !in 1..8000) reject(413,"文字须为 1–2000 字符")
                val bytes=ByteArray(size.toInt());var offset=0
                val until=System.nanoTime()+15_000_000_000L
                while(offset<bytes.size) {
                    if(System.nanoTime()>until) reject(408,"发送文字超时")
                    val n=input.read(bytes,offset,bytes.size-offset);if(n<0) reject(400,"文字不完整");offset+=n
                }
                val text=try {Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()} catch(_:Exception) {reject(400,"文字编码须为 UTF-8")}
                if(text.isBlank() || text.length>2000) reject(413,"文字须为 1–2000 字符")
                val event=HttpServerEvent.Text(text)
                if(!events.offer(event)) reject(429,"消息过多，请稍后重试")
                val accepted=try {event.reply.get(10,TimeUnit.SECONDS)} catch(_:Exception) {false}
                if(!accepted) reject(503,"脚本未能接收文字")
                json(socket,200,"已发送")
            } else {
                if(size>options.maxFileSizeMB*1024L*1024L) reject(413,"单个文件不能超过 ${options.maxFileSizeMB} MB")
                val name=try {URLDecoder.decode(target.removePrefix("/upload?name="),"UTF-8")} catch(_:Exception) {reject(400,"文件名无效")}
                if(name.isBlank() || name=="." || name==".." || name.length>120 || name.toByteArray(Charsets.UTF_8).size>240 || name.any {it<' ' || it=='\u007f' || it in "/\\:*?\"<>|"} || name.startsWith('.') || name.endsWith('.')) reject(400,"文件名无效或过长")
                if(!uploading.compareAndSet(false,true)) reject(409,"已有文件正在上传")
                try {upload(socket,input,name,size)} finally {uploading.set(false)}
            }
        } catch(e:RequestError) {runCatching {json(socket,e.status,e.message)}}
        catch(_:SocketTimeoutException) {runCatching {json(socket,408,"连接超时，请重试")}}
        catch(_:Exception) {if(!closed.get()) runCatching {json(socket,500,"接收失败，请重试")}}
    }
    private fun upload(socket:Socket,input:InputStream,name:String,size:Long) {
        var state=UploadProgress("http-upload-${UUID.randomUUID()}",name,0,size)
        try {
            HttpUploadFile(context,name).use {file->
                state=state.copy(name=file.name)
                val transfer=Transfer(state.id,file.name,size)
                activeProgress.set(transfer)
                if(!events.offer(HttpServerEvent.Wake)) reject(429,"消息过多，请稍后重试")
                val buffer=ByteArray(16384)
                val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30)
                var received=0L
                while(received<size) {
                    if(closed.get() || control.stopped.get()) throw java.io.IOException("上传已停止")
                    if(System.nanoTime()>deadline) throw java.io.IOException("上传超过 30 分钟")
                    val count=input.read(buffer,0,minOf(buffer.size.toLong(),size-received).toInt())
                    if(count<0) throw java.io.IOException("连接已断开，文件未完整接收")
                    file.output.write(buffer,0,count);received+=count
                    // Reuse the buffer and update only a counter, without allocating per chunk.
                    transfer.received=received
                }
                if(closed.get() || control.stopped.get()) throw java.io.IOException("上传已停止")
                file.commit()
                state=state.copy(name=file.name,receivedBytes=size,status="completed")
            }
        } catch(e:Exception) {
            state=state.copy(receivedBytes=activeProgress.get()?.received ?: 0,status="failed",error=when(e) {is SocketTimeoutException->"上传超时";else->e.message ?: "文件保存失败"})
            throw RequestError(if(e is SocketTimeoutException) 408 else 500,state.error!!)
        } finally {
            activeProgress.set(null)
            if(!closed.get() && !events.offer(HttpServerEvent.Progress(state),1,TimeUnit.SECONDS)) fail("脚本处理进度过慢，HTTP 服务已关闭")
        }
        json(socket,200,"已保存到 Download/",state.name)
    }
}
