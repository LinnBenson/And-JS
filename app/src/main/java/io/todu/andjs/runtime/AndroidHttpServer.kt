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
    private val manager:ConnectivityManager,private val network:Network?,private val address:Inet4Address,private val lease:Any
):HttpServerSession {
    companion object {
        private val owner=AtomicReference<Any?>()
        fun open(context:Context,options:HttpServerOptions,control:RunControl):AndroidHttpServer {
            val lease=Any()
            check(owner.compareAndSet(null,lease)) {"已有 HTTP 服务正在运行"}
            var server:AndroidHttpServer?=null
            try {
                val manager=context.getSystemService(ConnectivityManager::class.java)
                val hotspot=AndroidHotspots.hasActive()
                val wifi=if(hotspot) null else manager.allNetworks.firstOrNull {
                    val capabilities=manager.getNetworkCapabilities(it)
                    capabilities!=null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                val address=if(hotspot) {
                    val deadline=System.nanoTime()+3_000_000_000L
                    var addresses=AndroidHotspots.addresses(context)
                    while(addresses.isEmpty() && System.nanoTime()<deadline && !control.stopped.get()) {Thread.sleep(100);addresses=AndroidHotspots.addresses(context)}
                    addresses.singleOrNull() ?: error("无法确定唯一热点 IPv4 地址，请稍后重试")
                } else {
                    check(wifi!=null) {"请先连接 Wi-Fi 或启动热点"}
                    manager.getLinkProperties(wifi)?.linkAddresses?.map {it.address}?.filterIsInstance<Inet4Address>()?.firstOrNull {!it.isLoopbackAddress && !it.isLinkLocalAddress}
                        ?: error("Wi-Fi 尚未取得 IPv4 地址")
                }
                server=AndroidHttpServer(context,options,control,manager,wifi,address,lease)
                server.start()
                return server
            } catch(e:Exception) {
                server?.close();server?.awaitClosed();owner.compareAndSet(lease,null)
                throw e
            }
        }
    }
    private val chat=if(options.chat) HttpChatStore(java.io.File(context.noBackupFilesDir,"http-chat"),maxOf(256L,options.maxFileSizeMB.toLong())*1024*1024) else null
    override val sessionId:String? get()=chat?.sessionId
    override fun sendSystem(text:String):Any? {
        check(!closed.get()) {"聊天已结束"}
        require(text.isNotBlank() && text.length<=2000) {"系统消息须为 1–2000 字符"}
        return checkNotNull(chat).append("system","text",text=text)
    }
    private val cookieName="andjs_chat_${options.port}"
    private val closed=AtomicBoolean(false)
    private val listener=ServerSocket()
    private val sockets=ConcurrentHashMap.newKeySet<Socket>()
    private val events=ArrayBlockingQueue<HttpServerEvent>(8)
    private val uploading=AtomicBoolean(false)
    private class Transfer(val id:String,val name:String,val total:Long,val kind:String="file") {
        @Volatile var received=0L
        fun snapshot()=UploadProgress(id,name,received,total,kind=kind)
    }
    private val activeProgress=AtomicReference<Transfer?>()
    private val workers=object:ThreadPoolExecutor(0,2,15,TimeUnit.SECONDS,SynchronousQueue(),{task->Thread(task,"andjs-http-client").apply {isDaemon=true}}) {
        override fun terminated() {try {chat?.close()} finally {owner.compareAndSet(lease,null)}}
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
    private val page by lazy {context.assets.open(if(options.editor!=null) "http-editor.html" else if(options.chat) "http-chat.html" else "http-server.html").bufferedReader().use {it.readText()}.replace("__MAX_FILE_MB__",options.maxFileSizeMB.toString()).replace("__MAX_IMAGE_MB__",options.maxImageSizeMB.toString()).replace("__REQUIRES_TOKEN__",options.token.isNotEmpty().toString()).replace("__SERVER_NAME__",android.text.TextUtils.htmlEncode(options.name)).toByteArray(Charsets.UTF_8)}
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
            if(network!=null) {
                // An active request keeps Wi-Fi needed while serving, including on Wear OS.
                // Do not require INTERNET: a local-only Wi-Fi network is sufficient.
                // close() releases this request through unregisterNetworkCallback.
                manager.requestNetwork(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),networkCallback)
                registered=true
            }
        }
        if(network!=null && manager.getLinkProperties(network)?.linkAddresses?.none {it.address==address}!=false) fail("Wi-Fi 已断开")
        if(network==null) listener.soTimeout=1000
        unregisterStop=control.registerStop {close()}
        if(closed.get()) {unregisterStop?.invoke();unregisterStop=null;return}
        acceptThread=Thread({
            try {
                while(!closed.get()) {
                    if(network==null && address !in AndroidHotspots.addresses(context)) {fail("热点已停止或地址已变化");break}
                    val socket=try {listener.accept()} catch(_:SocketTimeoutException) {continue}
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
        events.forEach {when(it) {is HttpServerEvent.Text->it.reply.complete(false);is HttpServerEvent.Image->it.reply.complete("服务已关闭");else->Unit}}
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
    private fun responseHead(socket:Socket,status:Int,length:Long,type:String,extra:String="") {
        val reason=when(status) {200->"OK";400->"Bad Request";401->"Unauthorized";403->"Forbidden";404->"Not Found";408->"Request Timeout";409->"Conflict";411->"Length Required";413->"Payload Too Large";429->"Too Many Requests";503->"Service Unavailable";else->"Internal Server Error"}
        val headers="HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' blob:; frame-ancestors 'none'; form-action 'self'\r\n$extra\r\n"
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
    }
    private fun respond(socket:Socket,status:Int,bytes:ByteArray,type:String="application/json; charset=utf-8",extra:String="") {
        responseHead(socket,status,bytes.size.toLong(),type,extra)
        socket.getOutputStream().apply {write(bytes);flush()}
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
            if(options.editor!=null) {serveEditor(socket,input,method,target,headers);return}
            if(chat!=null) {serveChat(socket,input,method,target,headers);return}
            if(method!="POST" || (target!="/auth" && target!="/text" && !target.startsWith("/upload?name=") && !target.startsWith("/image?name="))) reject(404,"找不到页面")
            if(headers["origin"]?.let {it!="http://${headers["host"]}"}==true) reject(403,"请从服务网页发送")
            val supplied=headers["x-andjs-token"].orEmpty().toByteArray(Charsets.UTF_8)
            if(!MessageDigest.isEqual(supplied,token.toByteArray(Charsets.UTF_8))) reject(401,"访问码不正确")
            if(target=="/auth") {json(socket,200,"已连接");return}
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
                val image=target.startsWith("/image?name=")
                if(image && size !in 1..2097152) reject(413,"日志图片须为 1 B–2 MB；大图片可通过文件页上传")
                val name=try {URLDecoder.decode(target.substringAfter("?name="),"UTF-8")} catch(_:Exception) {reject(400,"文件名无效")}
                if(name.isBlank() || name=="." || name==".." || name.length>120 || name.toByteArray(Charsets.UTF_8).size>240 || name.any {it<' ' || it=='\u007f' || it in "/\\:*?\"<>|"} || name.startsWith('.') || name.endsWith('.')) reject(400,"文件名无效或过长")
                if(!uploading.compareAndSet(false,true)) reject(409,"已有文件正在上传")
                try {if(image) uploadImage(socket,input,name,size) else upload(socket,input,name,size)} finally {uploading.set(false)}
            }
        } catch(e:RequestError) {runCatching {json(socket,e.status,e.message)}}
        catch(_:SocketTimeoutException) {runCatching {json(socket,408,"连接超时，请重试")}}
        catch(_:Exception) {if(!closed.get()) runCatching {json(socket,500,"接收失败，请重试")}}
    }
    private fun serveEditor(socket:Socket,input:InputStream,method:String,target:String,headers:Map<String,String>) {
        if(headers["origin"]?.let {it!="http://${headers["host"]}"}==true) reject(403,"请从编辑网页发送")
        if(!MessageDigest.isEqual(headers["x-andjs-token"].orEmpty().toByteArray(Charsets.UTF_8),token.toByteArray(Charsets.UTF_8))) reject(401,"访问码不正确")
        if(method=="POST" && target=="/auth") {json(socket,200,"已连接");return}
        if(!((method=="GET" && target=="/scripts") || (method=="POST" && target=="/save"))) reject(404,"找不到页面")
        val body=if(method=="POST") {
            val size=headers["content-length"]?.toIntOrNull() ?: reject(411,"缺少文件大小")
            if(size !in 1..2097152) reject(413,"脚本及参数最大 2 MB")
            socket.soTimeout=15000
            val bytes=ByteArray(size);var offset=0
            val deadline=System.nanoTime()+30_000_000_000L
            while(offset<size) {
                if(System.nanoTime()>deadline) reject(408,"保存超时")
                val n=input.read(bytes,offset,size-offset);if(n<0) reject(400,"内容不完整");offset+=n
            }
            try {JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())} catch(_:Exception) {reject(400,"内容格式错误")}
        } else null
        val result=try {checkNotNull(options.editor).invoke(target,body)}
        catch(e:IllegalArgumentException) {reject(400,e.message ?: "参数错误")}
        catch(e:IllegalStateException) {reject(409,e.message ?: "脚本已变化，请刷新")}
        respond(socket,200,result.toString().toByteArray(Charsets.UTF_8))
    }
    private fun serveChat(socket:Socket,input:InputStream,method:String,target:String,headers:Map<String,String>) {
        val store=checkNotNull(chat)
        if(headers["origin"]?.let {it!="http://${headers["host"]}"}==true) reject(403,"请从聊天网页发送")
        val cookie=headers["cookie"]?.split(';')?.map {it.trim()}?.firstOrNull {it.startsWith("$cookieName=")}?.substringAfter('=')
        fun result(value:JSONObject,extra:String="")=respond(socket,200,value.toString().toByteArray(Charsets.UTF_8),extra=extra)
        fun length():Long=headers["content-length"]?.takeIf {it.isNotEmpty() && it.all {c->c in '0'..'9'}}?.toLongOrNull() ?: reject(411,"缺少有效文件大小")
        if(method=="POST" && target=="/join") {
            if(!MessageDigest.isEqual(headers["x-andjs-token"].orEmpty().toByteArray(Charsets.UTF_8),token.toByteArray(Charsets.UTF_8))) {store.leave(cookie);reject(401,"访问码不正确")}
            val (secret,id)=store.join(cookie)
            result(JSONObject().put("id",id).put("sessionId",store.sessionId),"Set-Cookie: $cookieName=$secret; HttpOnly; SameSite=Strict; Path=/\r\n")
            return
        }
        val sender=store.sender(cookie) ?: reject(401,"请先输入访问码加入聊天")
        if(method=="GET" && target.startsWith("/messages?after=")) {
            val after=target.substringAfter('=').toLongOrNull()?.takeIf {it>=0} ?: reject(400,"消息编号无效")
            result(store.messages(after));return
        }
        if(method=="GET" && target.startsWith("/attachment/")) {
            val id=target.substringAfter("/attachment/").substringBefore('?').toLongOrNull() ?: reject(404,"附件不存在")
            val (file,metadata)=store.attachment(id) ?: reject(404,"附件不存在")
            val inline=metadata.optString("type")=="image" && !target.endsWith("?download=1")
            val name=java.net.URLEncoder.encode(metadata.getString("name"),"UTF-8").replace("+","%20")
            val disposition=if(inline) "inline" else "attachment"
            file.inputStream().use {stream->
                responseHead(socket,200,file.length(),if(inline) metadata.getString("mime") else "application/octet-stream","Content-Disposition: $disposition; filename*=UTF-8''$name\r\n")
                val buffer=ByteArray(16384)
                val output=socket.getOutputStream()
                while(true) {if(closed.get()) return;val n=stream.read(buffer);if(n<0) break;output.write(buffer,0,n)}
                output.flush()
            }
            return
        }
        if(method!="POST") reject(404,"找不到页面")
        socket.soTimeout=15000
        if(target=="/leave") {
            // Keep the private identity cookie, but revoke data access until the next valid join.
            store.leave(cookie)
            result(JSONObject().put("ok",true));return
        }
        val size=length()
        var temp:java.io.File?=null
        var name:String?=null
        var message:Map<String,Any?>?=null
        try {
            if(target=="/send") {
                if(size !in 1..8000) reject(413,"文字须为 1–2000 字符")
                val bytes=ByteArray(size.toInt());var offset=0
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
                while(offset<bytes.size) {
                    if(System.nanoTime()>deadline) reject(408,"发送超时")
                    val n=input.read(bytes,offset,bytes.size-offset);if(n<0) reject(400,"文字不完整");offset+=n
                }
                val text=try {Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()} catch(_:Exception) {reject(400,"文字编码须为 UTF-8")}
                if(text.isBlank() || text.length>2000) reject(413,"文字须为 1–2000 字符")
                message=store.append(sender,"text",text=text)
            } else if(target.startsWith("/file?name=") || target.startsWith("/image?name=")) {
                val image=target.startsWith("/image")
                val limit=(if(image) options.maxImageSizeMB else options.maxFileSizeMB)*1024L*1024
                if(size>limit || (image && size==0L)) reject(413,"附件超出大小限制")
                name=try {URLDecoder.decode(target.substringAfter("?name="),"UTF-8")} catch(_:Exception) {reject(400,"文件名无效")}
                if(name.isBlank() || name.length>120 || name.toByteArray(Charsets.UTF_8).size>240 || name.any {it<' ' || it=='\u007f' || it in "/\\"}) reject(400,"文件名无效或过长")
                if(!uploading.compareAndSet(false,true)) reject(409,"已有附件正在上传，请稍后再试")
                try {
                    store.reserve(size)
                    temp=store.temporary()
                    temp.outputStream().use {out->
                        val buffer=ByteArray(16384);var received=0L
                        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30)
                        while(received<size) {
                            if(closed.get() || control.stopped.get()) throw java.io.IOException("聊天已结束")
                            if(System.nanoTime()>deadline) reject(408,"上传超过 30 分钟")
                            val n=input.read(buffer,0,minOf(buffer.size.toLong(),size-received).toInt())
                            if(n<0) throw java.io.IOException("附件未完整接收")
                            out.write(buffer,0,n);received+=n
                        }
                    }
                    var mime:String?=null
                    if(image) {
                        val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                        android.graphics.BitmapFactory.decodeFile(temp.absolutePath,bounds)
                        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outMimeType !in listOf("image/png","image/jpeg","image/webp","image/gif","image/bmp")) reject(400,"无法识别图片格式")
                        mime=bounds.outMimeType
                    }
                    message=store.append(sender,if(image) "image" else "file",name=name,size=size,temp=temp,mime=mime)
                } finally {uploading.set(false)}
            } else reject(404,"找不到页面")
            if(!events.offer(HttpServerEvent.Message(checkNotNull(message)),1,TimeUnit.SECONDS)) {fail("脚本处理消息过慢，聊天已关闭");reject(503,"聊天已关闭")}
            result(JSONObject().put("ok",true).put("id",message["id"]))
        } catch(e:RequestError) {throw e}
        catch(e:Exception) {
            if(!closed.get()) events.offer(HttpServerEvent.Progress(UploadProgress("chat-upload-${UUID.randomUUID()}",name ?: "消息",0,size,"failed",e.message ?: "接收失败","chat")))
            reject(400,e.message ?: "接收失败")
        } finally {temp?.delete()}
    }
    private fun uploadImage(socket:Socket,input:InputStream,name:String,size:Long) {
        var state=UploadProgress("http-image-${UUID.randomUUID()}",name,0,size,kind="image")
        try {
            val transfer=Transfer(state.id,name,size,"image")
            activeProgress.set(transfer)
            if(!events.offer(HttpServerEvent.Wake)) reject(429,"消息过多，请稍后重试")
            // Forward the original image to the existing image-log API.
            val bytes=ByteArray(size.toInt())
            val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30)
            var offset=0
            while(offset<bytes.size) {
                if(closed.get() || control.stopped.get()) throw java.io.IOException("上传已停止")
                if(System.nanoTime()>deadline) throw java.io.IOException("上传超过 30 分钟")
                val count=input.read(bytes,offset,minOf(16384,bytes.size-offset))
                if(count<0) throw java.io.IOException("图片未完整接收")
                offset+=count;transfer.received=offset.toLong()
            }
            val event=HttpServerEvent.Image(java.util.Base64.getEncoder().encodeToString(bytes))
            if(!events.offer(event)) reject(429,"消息过多，请稍后重试")
            val result=try {event.reply.get(15,TimeUnit.SECONDS)} catch(_:Exception) {
                event.reply.complete("图片日志处理超时")
                "图片日志处理超时"
            }
            if(result!=null) throw java.io.IOException(result)
            state=state.copy(receivedBytes=size,status="completed")
        } catch(e:Exception) {
            state=state.copy(receivedBytes=activeProgress.get()?.received ?: 0,status="failed",error=e.message ?: "图片显示失败")
            throw RequestError(if(e is SocketTimeoutException) 408 else 400,state.error!!)
        } finally {
            activeProgress.set(null)
            if(!closed.get() && !events.offer(HttpServerEvent.Progress(state),1,TimeUnit.SECONDS)) fail("脚本处理进度过慢，HTTP 服务已关闭")
        }
        json(socket,200,"已显示到日志",name)
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
