package io.todu.andjs.runtime

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal class WebSocketFailure(val details:JSONObject):IOException(details.optString("message"))
internal fun wsFailure(code:String,message:String,url:String="",status:Int=0,closeCode:Int?=null,reason:String?=null)=WebSocketFailure(
    JSONObject().put("name","WebSocketError").put("code",code).put("message",message).put("url",url).put("status",status)
        .apply {closeCode?.let {put("closeCode",it)};reason?.let {put("reason",it)}})

/** Network callbacks only enqueue immutable frames; JavaScript always runs on its original thread. */
internal class ScriptWebSockets(private val control:RunControl,private val lowMemory:Boolean) {
    private val lock=Any()
    private val connections=mutableSetOf<Connection>()
    private var disposed=false
    private var openedCount=0
    private val base=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val unregister=control.registerStop {cancelAll()}
    private val maxMessage=if(lowMemory) 64*1024 else 256*1024
    private val maxQueue=if(lowMemory) 128*1024 else 1024*1024
    private val maxMessages=if(lowMemory) 8 else 32
    private val maxWait=if(lowMemory) 15000L else 120000L
    fun messageLimit()=maxMessage
    private fun number(options:JSONObject,key:String,default:Long,max:Long,minimum:Long=1):Long {
        if(!options.has(key)) return default
        val value=options.get(key) as? Number ?: throw wsFailure("INVALID_OPTIONS","$key 须为整数")
        val n=value.toDouble()
        if(!n.isFinite() || n%1!=0.0 || n<minimum || n>max) throw wsFailure("INVALID_OPTIONS","$key 超出范围")
        return n.toLong()
    }
    fun connect(address:String,options:JSONObject):Connection {
        if(!address.startsWith("wss://",true) && !address.startsWith("ws://",true)) throw wsFailure("INVALID_OPTIONS","地址须以 wss:// 或 ws:// 开头")
        val url=(if(address.startsWith("wss://",true)) "https://"+address.substring(6) else "http://"+address.substring(5)).toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) {"URL 不应包含账号密码，请使用 auth"}
        val request=Request.Builder().url(url)
        listOf("headers","auth").forEach {key->require(!options.has(key) || options.optJSONObject(key)!=null) {"$key 须为对象"}}
        require(!options.has("protocols") || options.optJSONArray("protocols")!=null) {"protocols 须为字符串数组"}
        options.optJSONObject("headers")?.let {headers->headers.keys().forEach {key->
            require(key.lowercase() !in listOf("connection","upgrade","host","sec-websocket-key","sec-websocket-version","sec-websocket-extensions","sec-websocket-protocol")) {"不能覆盖 WebSocket 协议请求头"}
            val value=headers.get(key);require(value is String) {"请求头值须为字符串"}
            request.header(key,value)
        }}
        options.optJSONObject("auth")?.let {auth->
            when(auth.getString("type")) {
                "bearer"->request.header("Authorization","Bearer "+auth.getString("token"))
                "basic"->request.header("Authorization",Credentials.basic(auth.getString("username"),auth.optString("password"),Charsets.UTF_8))
                else->throw wsFailure("INVALID_OPTIONS","auth.type 须为 basic 或 bearer")
            }
        }
        options.opt("cookies")?.let {value->
            require(request.build().header("Cookie")==null) {"cookies 与 Cookie 请求头不能同时指定"}
            val text=when(value) {
                is String->value
                is JSONObject->value.keys().asSequence().map {name->
                    require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {"Cookie 名称无效"}
                    val item=value.get(name);require(item is String && item.all {it.code in 0x21..0x7e && it !in ";,\"\\"}) {"Cookie 值无效"}
                    "$name=$item"
                }.joinToString("; ")
                else->throw wsFailure("INVALID_OPTIONS","cookies 须为字符串或对象")
            }
            require(text.length<=16384) {"Cookie 过长"}
            request.header("Cookie",text)
        }
        options.optJSONArray("protocols")?.let {protocols->
            require(protocols.length() in 1..8) {"protocols 须为 1–8 项"}
            val names=(0 until protocols.length()).map {protocols.getString(it).also {name->
                require(name.length in 1..100 && name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {"子协议名称无效"}
            }}
            require(names.distinct().size==names.size) {"子协议不能重复"}
            request.header("Sec-WebSocket-Protocol",names.joinToString(", "))
        }
        val connectTimeout=number(options,"connectTimeout",10000,if(lowMemory) 15000 else 30000)
        val receiveTimeout=number(options,"receiveTimeout",15000,maxWait)
        val pingInterval=number(options,"pingInterval",20000,60000,0)
        require(pingInterval==0L || pingInterval>=5000) {"pingInterval 须为 0 或 5000–60000 毫秒"}
        val connection=Connection(address,receiveTimeout)
        synchronized(lock) {
            if(disposed || control.stopped.get()) throw wsFailure("CANCELLED","脚本已停止",address)
            if(connections.size>=(if(lowMemory) 2 else 4)) throw wsFailure("CONNECTION_LIMIT","同时打开的 WebSocket 连接过多",address)
            if(openedCount>=(if(lowMemory) 8 else 32)) throw wsFailure("CONNECTION_LIMIT","本次运行的连接尝试次数已达上限",address)
            openedCount++
            connections.add(connection)
        }
        try {
            connection.start(request.build(),connectTimeout,pingInterval)
            connection.awaitOpen(connectTimeout)
            return connection
        } catch(e:Exception) {connection.abort(wsFailure("CANCELLED","连接未完成",address));throw e}
    }
    private fun released(connection:Connection) {synchronized(lock) {connections.remove(connection)}}
    private fun cancelAll() {
        val current=synchronized(lock) {disposed=true;connections.toList()}
        current.forEach {it.abort(wsFailure("CANCELLED","脚本已停止",it.url))}
        base.dispatcher.cancelAll()
    }
    fun dispose() {
        val current=synchronized(lock) {disposed=true;connections.toList()}
        try {current.forEach {it.finish()}} finally {
            unregister()
            base.dispatcher.cancelAll();base.connectionPool.evictAll();base.dispatcher.executorService.shutdownNow()
        }
    }
    private data class Frame(val text:String?,val bytes:ByteString?,val size:Int)
    inner class Connection internal constructor(val url:String,private val receiveTimeout:Long) {
        private val monitor=java.lang.Object()
        private var socket:WebSocket?=null
        private var state="connecting"
        private var opened=false
        private var failure:WebSocketFailure?=null
        private var closeCode=1000
        private var closeReason=""
        private val queue=ArrayDeque<Frame>()
        private var queuedBytes=0
        private var localClose=false
        private var cleanClose=false
        private fun errorClosed()=failure ?: wsFailure("CLOSED","连接已关闭",url,closeCode=closeCode,reason=closeReason)
        private fun checkCancelled() {
            if(control.stopped.get() || Thread.currentThread().isInterrupted) throw wsFailure("CANCELLED","脚本已停止",url)
        }
        private fun accept(frame:Frame) {
            var error:WebSocketFailure?=null
            synchronized(monitor) {
                if(state!="open" || localClose) return
                error=when {
                    frame.size>maxMessage->wsFailure("MESSAGE_TOO_LARGE","收到的消息超过大小限制",url)
                    queue.size>=maxMessages || queuedBytes+frame.size>maxQueue->wsFailure("QUEUE_OVERFLOW","接收消息积压过多，连接已断开",url)
                    else->null
                }
                if(error==null) {queue.addLast(frame);queuedBytes+=frame.size;monitor.notifyAll()}
            }
            error?.let {abort(it)}
        }
        fun start(request:Request,timeout:Long,pingInterval:Long) {
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout)
            val boundedDns=InterruptibleDns(Dns.SYSTEM,control)
            val client=base.newBuilder().connectTimeout(timeout,TimeUnit.MILLISECONDS).readTimeout(0,TimeUnit.MILLISECONDS)
                .writeTimeout(timeout,TimeUnit.MILLISECONDS).pingInterval(pingInterval,TimeUnit.MILLISECONDS)
                .dns(object:Dns {override fun lookup(hostname:String):List<java.net.InetAddress> {
                    boundedDns.begin(deadline)
                    try {return boundedDns.lookup(hostname)} finally {boundedDns.end()}
                }}).build()
            synchronized(monitor) {
                if(state!="connecting") throw errorClosed()
                checkCancelled()
                socket=client.newWebSocket(request,object:WebSocketListener() {
                    override fun onOpen(webSocket:WebSocket,response:Response) {
                        val selected=response.header("Sec-WebSocket-Protocol")
                        val offered=request.header("Sec-WebSocket-Protocol")?.split(',')?.map {it.trim()}.orEmpty()
                        if(selected!=null && selected !in offered) {abort(wsFailure("HANDSHAKE_ERROR","服务器选择了未提供的子协议",url,response.code));return}
                        synchronized(monitor) {
                            if(state!="connecting") {webSocket.cancel();return}
                            opened=true;state="open";monitor.notifyAll()
                        }
                    }
                    override fun onMessage(webSocket:WebSocket,text:String) {
                        if(text.length>maxMessage) abort(wsFailure("MESSAGE_TOO_LARGE","收到的消息超过大小限制",url))
                        else accept(Frame(text,null,text.toByteArray(Charsets.UTF_8).size))
                    }
                    override fun onMessage(webSocket:WebSocket,bytes:ByteString) {accept(Frame(null,bytes,bytes.size))}
                    override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {
                        synchronized(monitor) {
                            if(state=="closed") return
                            closeCode=code;closeReason=reason;state="closing";monitor.notifyAll()
                        }
                        runCatching {webSocket.close(if(code==1005) 1000 else code,reason)}.onFailure {abort(wsFailure("CONNECTION_ERROR","关闭握手失败",url))}
                    }
                    override fun onClosed(webSocket:WebSocket,code:Int,reason:String) {
                        synchronized(monitor) {cleanClose=true;state="closed";closeCode=code;closeReason=reason;monitor.notifyAll()}
                        released(this@Connection)
                    }
                    override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {
                        val wasOpen=synchronized(monitor) {opened}
                        val code=when {
                            response!=null->"HANDSHAKE_ERROR"
                            t is SSLException->"TLS_ERROR"
                            t is UnknownHostException->"DNS_ERROR"
                            t is SocketTimeoutException && !wasOpen->"CONNECT_TIMEOUT"
                            else->"CONNECTION_ERROR"
                        }
                        runCatching {response?.close()}
                        abort(wsFailure(code,when(code) {
                            "HANDSHAKE_ERROR"->"服务器未接受 WebSocket 握手"
                            "TLS_ERROR"->"安全连接或证书验证失败"
                            "DNS_ERROR"->"域名解析失败或繁忙"
                            "CONNECT_TIMEOUT"->"连接超时"
                            else->"WebSocket 连接中断"
                        },url,response?.code ?: 0))
                    }
                })
            }
        }
        fun awaitOpen(timeout:Long) {
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout)
            synchronized(monitor) {
                while(state=="connecting") {
                    checkCancelled()
                    val left=deadline-System.nanoTime()
                    if(left<=0) throw wsFailure("CONNECT_TIMEOUT","连接超时",url)
                    monitor.wait(TimeUnit.NANOSECONDS.toMillis(left).coerceIn(1,100))
                }
                checkCancelled()
                failure?.let {throw it}
                if(!opened) throw errorClosed()
            }
        }
        fun send(text:String):Boolean {
            if(text.length>maxMessage) throw wsFailure("MESSAGE_TOO_LARGE","发送消息超过大小限制",url)
            return sendFrame(text,null,text.toByteArray(Charsets.UTF_8).size)
        }
        fun send(bytes:ByteString):Boolean=sendFrame(null,bytes,bytes.size)
        private fun sendFrame(text:String?,bytes:ByteString?,size:Int):Boolean=synchronized(monitor) {
            checkCancelled()
            if(state!="open") throw errorClosed()
            if(size>maxMessage) throw wsFailure("MESSAGE_TOO_LARGE","发送消息超过大小限制",url)
            val ws=socket ?: throw errorClosed()
            if(ws.queueSize()+size>maxQueue) throw wsFailure("SEND_QUEUE_FULL","发送队列已满，请稍后发送",url)
            val accepted=if(text!=null) ws.send(text) else ws.send(bytes!!)
            if(!accepted) throw errorClosed()
            true
        }
        fun receive(timeout:Long?=null):JSONObject {
            val wait=timeout ?: receiveTimeout
            if(wait !in 1..maxWait) throw wsFailure("INVALID_OPTIONS","接收超时超出范围",url)
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(wait)
            synchronized(monitor) {
                while(true) {
                    checkCancelled()
                    failure?.let {throw it}
                    if(queue.isNotEmpty()) {
                        val frame=queue.removeFirst();queuedBytes-=frame.size
                        return JSONObject().put("type",if(frame.text!=null) "text" else "binary").put("size",frame.size).apply {
                            if(frame.text!=null) put("text",frame.text) else put("base64",frame.bytes!!.base64())
                        }
                    }
                    if(state=="closed" || state=="closing") throw errorClosed()
                    val left=deadline-System.nanoTime()
                    if(left<=0) throw wsFailure("RECEIVE_TIMEOUT","等待消息超时，连接仍可继续使用",url)
                    monitor.wait(TimeUnit.NANOSECONDS.toMillis(left).coerceIn(1,100))
                }
            }
        }
        fun close(code:Int=1000,reason:String=""):Boolean {
            if(code!=1000 && code !in 3000..4999) throw wsFailure("INVALID_OPTIONS","关闭码须为 1000 或 3000–4999",url)
            if(reason.length>123 || reason.toByteArray(Charsets.UTF_8).size>123) throw wsFailure("INVALID_OPTIONS","关闭原因不能超过 123 个 UTF-8 字节",url)
            synchronized(monitor) {
                if(state=="closed" || localClose) {queue.clear();queuedBytes=0;return false}
                localClose=true;state="closing";closeCode=code;closeReason=reason;queue.clear();queuedBytes=0;monitor.notifyAll()
                socket?.close(code,reason)
            }
            val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500)
            try {
                synchronized(monitor) {
                    while(state!="closed" && System.nanoTime()<deadline && !control.stopped.get()) {
                        monitor.wait(TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()).coerceIn(1,50))
                    }
                }
            } finally {abort(null)}
            return true
        }
        fun closeInfo():JSONObject=synchronized(monitor) {
            JSONObject().put("closeCode",if(failure!=null) 1006 else closeCode).put("reason",if(failure!=null) failure!!.details.optString("message") else closeReason)
                .put("wasClean",cleanClose).put("url",url)
        }
        fun finish() {try {close()} catch(_:Exception) {abort(null)} finally {synchronized(monitor) {queue.clear();queuedBytes=0}}}
        fun abort(error:WebSocketFailure?) {
            val ws=synchronized(monitor) {
                if(state=="closed") return
                failure=error;state="closed";queue.clear();queuedBytes=0;monitor.notifyAll();socket
            }
            ws?.cancel()
            released(this)
        }
    }
}
