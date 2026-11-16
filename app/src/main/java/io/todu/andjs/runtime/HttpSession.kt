package io.todu.andjs.runtime

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated in-memory cookie jar and connection pool; retries are controlled by HttpClient. */
class HttpSession(private val root:File,private val control:RunControl,private val lowMemory:Boolean=false,private val dns:Dns=Dns.SYSTEM) {
    companion object {
        private val cleanedRoots=mutableSetOf<String>()
        @Synchronized private fun cleanAbandoned(root:File) {
            if(!cleanedRoots.add(root.canonicalPath)) return
            root.listFiles()?.filter {it.isFile && (it.name.matches(Regex("andjs-temp-[0-9a-f-]{36}\\.bin")) || it.name.matches(Regex("andjs-part-[0-9]+\\.tmp")))}?.forEach {it.delete()}
        }
    }
    init {cleanAbandoned(root)}
    private val temporaryFiles=java.util.Collections.synchronizedList(mutableListOf<File>())
    private val cookies=mutableListOf<Cookie>()
    private val jar=object:CookieJar {
        override fun saveFromResponse(url:HttpUrl, incoming:List<Cookie>) = synchronized(cookies) {
            incoming.forEach {c->cookies.removeAll {it.name==c.name && it.domain==c.domain && it.path==c.path};if(c.expiresAt>System.currentTimeMillis()) cookies.add(c)}
            while(cookies.size>200) cookies.removeAt(0)
        }
        override fun loadForRequest(url:HttpUrl):List<Cookie> = synchronized(cookies) {
            cookies.removeAll {it.expiresAt<System.currentTimeMillis()};cookies.filter {it.matches(url)}
        }
    }
    private val requestDns=InterruptibleDns(dns,control)
    private val base=OkHttpClient.Builder().retryOnConnectionFailure(false).cookieJar(jar).dns(requestDns).build()
    fun cancelRequests() {base.dispatcher.cancelAll()}
    fun close() { base.dispatcher.cancelAll();base.connectionPool.evictAll(); base.dispatcher.executorService.shutdown();synchronized(temporaryFiles) {temporaryFiles.forEach {it.delete()};temporaryFiles.clear()};clearCookies() }
    fun clearCookies() {synchronized(cookies) {cookies.clear()}}
    private fun cookieValues(url:HttpUrl,value:Any):List<Cookie> {
        val entries=when(value) {
            is JSONObject -> value.keys().asSequence().map {name->val text=value.get(name);require(text is String) {"Cookie 值须为字符串"};name to text}.toList()
            is String -> if(value.isBlank()) emptyList() else value.split(';').map {part->
                val split=part.indexOf('=');require(split>0) {"Cookie 须为 name=value，以分号分隔"}
                part.substring(0,split).trim() to part.substring(split+1).trim()
            }
            else -> throw IllegalArgumentException("Cookie 须为字符串或名称到字符串的对象")
        }
        require(entries.size<=100) {"Cookie 最多 100 项"}
        return entries.map {(name,text)->
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {"Cookie 名称无效"}
            require(text.length<=4096 && text.all {it.code in 0x21..0x7e && it !in ";,\"\\"}) {"Cookie 值须为不含分号、逗号、引号和反斜杠的 ASCII 字符串"}
            Cookie.Builder().name(name).value(text).hostOnlyDomain(url.host).path("/").apply {if(url.isHttps) secure()}.build()
        }
    }
    fun setCookies(url:String,value:Any) {
        val address=url.toHttpUrl()
        jar.saveFromResponse(address,cookieValues(address,value))
    }
    fun getCookies(url:String):JSONArray=JSONArray().apply {
        jar.loadForRequest(url.toHttpUrl()).forEach {c->put(JSONObject().put("name",c.name).put("value",c.value)
            .put("domain",c.domain).put("path",c.path).put("secure",c.secure).put("httpOnly",c.httpOnly)
            .put("hostOnly",c.hostOnly).put("expiresAt",if(c.persistent) c.expiresAt else JSONObject.NULL))}
    }
    private fun requestCookieJar(url:HttpUrl,option:Any?,headers:Headers.Builder):CookieJar {
        val headerValues=headers.build().values("Cookie")
        headers.removeAll("Cookie")
        require(option==null || option is Boolean || option is String || option is JSONObject) {"cookies 须为 boolean、字符串或对象"}
        val explicit=when(option) {is String,is JSONObject->option;else->null}
        require(explicit==null || headerValues.isEmpty()) {"cookies 与 Cookie 请求头不能同时指定"}
        val manual=if(explicit!=null) cookieValues(url,explicit) else if(headerValues.isNotEmpty()) cookieValues(url,headerValues.joinToString("; ")) else emptyList()
        val enabled=option!=false
        return object:CookieJar {
            override fun saveFromResponse(address:HttpUrl,cookies:List<Cookie>) {
                if(enabled) jar.saveFromResponse(address,cookies)
            }
            override fun loadForRequest(address:HttpUrl):List<Cookie> {
                val saved=if(enabled) jar.loadForRequest(address) else emptyList()
                // Explicit cookies apply to this request's origin only, never a redirect target.
                val sameOrigin=address.scheme==url.scheme && address.host==url.host && address.port==url.port
                if(!sameOrigin || manual.isEmpty()) return saved
                val names=manual.map {it.name}.toSet()
                return saved.filter {it.name !in names}+manual
            }
        }
    }
    private fun file(name:String):File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,120}")) && name!="." && name!="..") {"文件名只能包含字母、数字、点、横线和下划线"}
        root.mkdirs()
        return File(root,name).canonicalFile.also {require(it.parentFile==root.canonicalFile) {"文件路径无效"}}
    }
    private fun values(value:Any?):List<String> = if(value is JSONArray) (0 until value.length()).map {value.get(it).toString()} else listOf(if(value==JSONObject.NULL) "" else value.toString())
    private fun timeout(o:JSONObject,key:String,default:Long):Long {
        if(!o.has(key)) return if(lowMemory) default.coerceAtMost(15000) else default
        val value=o.get(key) as? Number ?: throw IllegalArgumentException("$key 须为毫秒整数")
        val n=value.toDouble()
        require(n.isFinite() && n%1==0.0 && n>=1 && n<=if(lowMemory) 15000 else 120000) {"$key 超出设备允许的超时范围"}
        return n.toLong()
    }
    fun request(url:String,o:JSONObject):JSONObject {
        if(control.stopped.get() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已停止")
        val method=o.optString("method","GET").uppercase(java.util.Locale.ROOT)
        require(method.matches(Regex("[A-Z]+")) && method!="CONNECT") {"不支持的请求方法"}
        val urlBuilder=url.toHttpUrl().newBuilder()
        o.optJSONObject("query")?.let {query->query.keys().forEach {key->values(query.get(key)).forEach {urlBuilder.addQueryParameter(key,it)}}}
        val builder=Request.Builder().url(urlBuilder.build())
        val requestHeaders=Headers.Builder()
        o.optJSONObject("headers")?.let {headers->headers.keys().forEach {key->values(headers.get(key)).forEach {requestHeaders.add(key,it)}}}
        val selectedJar=requestCookieJar(urlBuilder.build(),o.opt("cookies"),requestHeaders)
        builder.headers(requestHeaders.build())
        o.optJSONObject("auth")?.let {auth->
            when(auth.optString("type")) {
                "basic"->builder.header("Authorization",Credentials.basic(auth.getString("username"),auth.optString("password"),Charsets.UTF_8))
                "bearer"->builder.header("Authorization","Bearer "+auth.getString("token"))
                else->throw IllegalArgumentException("auth.type 须为 basic 或 bearer")
            }
        }
        val bodyKeys=listOf("data","json","form","text","base64","multipart","file").filter {o.has(it)}
        require(bodyKeys.size<=1) {"请求体只能选择一种格式"}
        val type=o.optString("contentType","")
        val body:RequestBody?=when(bodyKeys.firstOrNull()) {
            "data" -> o.get("data").let { value ->
                val structured=value is JSONObject || value is JSONArray
                val content=if(structured) value.toString() else if(value==JSONObject.NULL) "null" else value.toString()
                content.toRequestBody(type.ifEmpty {if(structured) "application/json; charset=utf-8" else "text/plain; charset=utf-8"}.toMediaType())
            }
            "json" -> JSONArray().put(o.get("json")).toString().let {it.substring(1,it.length-1)}.toRequestBody((type.ifEmpty {"application/json; charset=utf-8"}).toMediaType())
            "form" -> FormBody.Builder().apply {val form=o.getJSONObject("form");form.keys().forEach {key->values(form.get(key)).forEach {add(key,it)}}}.build()
            "text" -> o.getString("text").toRequestBody((type.ifEmpty {"text/plain; charset=utf-8"}).toMediaType())
            "base64" -> Base64.getDecoder().decode(o.getString("base64")).toRequestBody((type.ifEmpty {"application/octet-stream"}).toMediaType())
            "file" -> file(o.getString("file")).also {require(it.isFile) {"上传文件不存在"}}.asRequestBody((type.ifEmpty {"application/octet-stream"}).toMediaType())
            "multipart" -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                val parts=o.getJSONArray("multipart");require(parts.length() in 1..100) {"multipart 须为 1–100 项"}
                for(i in 0 until parts.length()) {
                    val p=parts.getJSONObject(i);val name=p.getString("name")
                    require(listOf("value","base64","file").count {p.has(it)}==1) {"每个 multipart 项须有一种内容"}
                    if(p.has("value")) addFormDataPart(name,p.get("value").toString()) else {
                        val media=p.optString("contentType","application/octet-stream").toMediaType()
                        val data=if(p.has("file")) file(p.getString("file")).also {require(it.isFile)}.asRequestBody(media) else Base64.getDecoder().decode(p.getString("base64")).toRequestBody(media)
                        addFormDataPart(name,p.optString("filename","upload.bin"),data)
                    }
                }
            }.build()
            else -> if(method in listOf("POST","PUT","PATCH","PROPPATCH","REPORT")) ByteArray(0).toRequestBody(null) else null
        }
        require(!(method in listOf("GET","HEAD") && body!=null)) {"GET/HEAD 不支持请求体"}
        builder.method(method,body)
        val callTimeout=timeout(o,"timeout",30000)
        var phase="connect"
        val client=base.newBuilder().eventListener(object:EventListener() {
            override fun dnsStart(call:Call,domainName:String) {phase="dns"}
            override fun connectStart(call:Call,inetSocketAddress:java.net.InetSocketAddress,proxy:java.net.Proxy) {phase="connect"}
            override fun secureConnectStart(call:Call) {phase="tls"}
            override fun requestHeadersStart(call:Call) {phase="write"}
            override fun responseHeadersStart(call:Call) {phase="read"}
            override fun responseBodyStart(call:Call) {phase="read"}
        }).callTimeout(callTimeout,TimeUnit.MILLISECONDS)
            .connectTimeout(timeout(o,"connectTimeout",10000),TimeUnit.MILLISECONDS)
            .readTimeout(timeout(o,"readTimeout",30000),TimeUnit.MILLISECONDS)
            .writeTimeout(timeout(o,"writeTimeout",30000),TimeUnit.MILLISECONDS)
            .followRedirects(o.optBoolean("followRedirects",true)).followSslRedirects(false)
            .cookieJar(selectedJar).build()
        val responseType=o.optString("responseType","auto")
        require(responseType in listOf("auto","text","json","base64","file")) {"responseType 无效"}
        val max=o.optLong("maxBytes",if(lowMemory) {if(responseType=="file") 16L*1024*1024 else 2L*1024*1024} else if(responseType=="file") 100L*1024*1024 else 8L*1024*1024)
        require(max in 1..(if(lowMemory) {if(responseType=="file") 16L*1024*1024 else 2L*1024*1024} else if(responseType=="file") 256L*1024*1024 else 16L*1024*1024)) {"maxBytes 超出范围"}
        val call=client.newCall(builder.build())
        val release=control.registerStop {call.cancel()}
        val start=System.nanoTime()
        requestDns.begin(start+TimeUnit.MILLISECONDS.toNanos(callTimeout))
        try {
            call.execute().use {response->
                val headers=JSONObject();response.headers.names().forEach {headers.put(it.lowercase(java.util.Locale.ROOT),JSONArray(response.headers.values(it)))}
                val result=JSONObject().put("isJson",false).put("isFile",false).put("status",response.code).put("statusText",response.message).put("ok",response.isSuccessful)
                    .put("url",response.request.url.toString()).put("headers",headers).put("contentType",response.body?.contentType()?.toString() ?: "")
                if(o.has("filename")) require(!o.getString("filename").startsWith("andjs-temp-") && !o.getString("filename").startsWith("andjs-part-")) {"文件名使用了临时文件保留前缀"}
                val destination=if(responseType=="file" && response.isSuccessful) file(o.optString("filename","andjs-temp-"+UUID.randomUUID().toString()+".bin")).also {if(!o.has("filename")) temporaryFiles.add(it)} else null
                if(destination!=null) require(!destination.exists() || o.optBoolean("overwrite",false)) {"下载文件已存在"}
                phase=if(destination!=null) "file" else "read"
                val temp=destination?.let {File.createTempFile("andjs-part-",".tmp",root)}
                var count=0L
                try {
                    val memory=if(temp==null) ByteArrayOutputStream() else null
                    (temp?.outputStream() ?: memory!!).use {out->
                        response.body?.byteStream()?.use {input->
                            val bytes=ByteArray(8192)
                            while(true) {
                                phase="read"
                                if(control.stopped.get() || Thread.currentThread().isInterrupted) throw java.io.IOException("请求已停止")
                                val n=input.read(bytes);if(n<0) break
                                count+=n
                                val limit=if(responseType=="file" && !response.isSuccessful) minOf(max,if(lowMemory) 262144L else 1048576L,o.optLong("_errorMaxBytes",Long.MAX_VALUE).coerceAtLeast(1)) else max
                                if(count>limit) throw HttpFailure(JSONObject().put("name","HttpError").put("code","RESPONSE_TOO_LARGE").put("message","响应超过 maxBytes 限制").put("phase","read").put("status",response.code).put("retryable",false))
                                phase=if(temp!=null) "file" else "read"
                                out.write(bytes,0,n)
                                phase="read"
                            }
                        }
                    }
                    if(control.stopped.get() || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已停止")
                    if(temp!=null) {
                        phase="file"
                        if(o.optBoolean("overwrite",false)) Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
                        else Files.move(temp.toPath(),destination.toPath())
                        result.put("file",destination.name).put("isFile",response.isSuccessful)
                    } else {
                        val bytes=memory!!.toByteArray()
                        if(responseType=="base64") result.put("base64",Base64.getEncoder().encodeToString(bytes))
                        else result.put("text",String(bytes,response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                    }
                    result.put("size",count).put("elapsedMs",(System.nanoTime()-start)/1_000_000)
                    if(o.optBoolean("throwOnHttpError",false) && !response.isSuccessful) throw httpStatusFailure(result,method)
                    return result
                } finally {temp?.delete()}
            }
        } catch(e:Exception) {throw httpFailure(e,url,method,phase)}
        finally {requestDns.end();release()}
    }
}

/** System DNS may ignore both socket cancellation and thread interruption. Bound the caller's
 * wait independently, and cap abandoned native lookups across all script sessions. */
internal class InterruptibleDns(private val delegate:Dns,private val control:RunControl):Dns {
    companion object {
        private val workers=java.util.concurrent.ThreadPoolExecutor(
            0,4,30,TimeUnit.SECONDS,java.util.concurrent.SynchronousQueue<Runnable>(),
            java.util.concurrent.ThreadFactory {task->Thread(task,"AndJS-DNS").apply {isDaemon=true}}
        )
    }
    // OkHttp's synchronous execute resolves names on the calling thread, including redirects.
    // Keep one resolver identity per session so connection pooling continues to work.
    private val deadline=ThreadLocal<Long>()
    fun begin(value:Long) {deadline.set(value)}
    fun end() {deadline.remove()}
    override fun lookup(hostname:String):List<java.net.InetAddress> {
        val until=checkNotNull(deadline.get()) {"缺少网络请求时限"}
        fun remaining():Long {
            if(control.stopped.get()) throw java.io.InterruptedIOException("请求已停止")
            return (until-System.nanoTime()).also {
                if(it<=0) throw java.net.SocketTimeoutException("域名解析超时")
            }
        }
        remaining()
        val result=try {
            workers.submit(java.util.concurrent.Callable {delegate.lookup(hostname)})
        } catch(e:java.util.concurrent.RejectedExecutionException) {
            throw java.net.UnknownHostException("域名解析繁忙，请稍后重试").apply {initCause(e)}
        }
        try {
            while(true) {
                try {
                    val addresses=result.get(minOf(remaining(),TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS)
                    remaining()
                    return addresses
                } catch(_:java.util.concurrent.TimeoutException) {
                    // Recheck stop and the whole-request deadline even if native DNS is stuck.
                }
            }
        } catch(e:java.util.concurrent.ExecutionException) {
            throw (e.cause as? java.io.IOException ?: java.io.IOException("域名解析失败",e.cause))
        } catch(e:InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("域名解析已中断").apply {initCause(e)}
        } finally {result.cancel(true)}
    }
}
