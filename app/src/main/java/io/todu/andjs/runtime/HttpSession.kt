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

/** One cookie session per script; no automatic retries of side-effecting requests. */
class HttpSession(private val root:File,private val control:RunControl,private val lowMemory:Boolean=false,private val dns:Dns=Dns.SYSTEM) {
    companion object {
        private val cleanedRoots=mutableSetOf<String>()
        @Synchronized private fun cleanAbandoned(root:File) {
            if(!cleanedRoots.add(root.canonicalPath)) return
            root.listFiles()?.filter {it.isFile && (it.name.matches(Regex("andjs-temp-[0-9a-f-]{36}\\.bin")) || it.name.matches(Regex("andjs-part-[0-9]+\\.tmp")))}?.forEach {it.delete()}
        }
    }
    init {cleanAbandoned(root)}
    private val temporaryFiles=mutableListOf<File>()
    private val cookies=mutableListOf<Cookie>()
    private val jar=object:CookieJar {
        override fun saveFromResponse(url:HttpUrl, incoming:List<Cookie>) = synchronized(cookies) {
            incoming.forEach {c->cookies.removeAll {it.name==c.name && it.domain==c.domain && it.path==c.path};cookies.add(c)}
        }
        override fun loadForRequest(url:HttpUrl):List<Cookie> = synchronized(cookies) {
            cookies.removeAll {it.expiresAt<System.currentTimeMillis()};cookies.filter {it.matches(url)}
        }
    }
    private val requestDns=InterruptibleDns(dns,control)
    private val base=OkHttpClient.Builder().retryOnConnectionFailure(false).cookieJar(jar).dns(requestDns).build()
    fun close() { base.dispatcher.cancelAll();base.connectionPool.evictAll(); base.dispatcher.executorService.shutdown();temporaryFiles.forEach {it.delete()};temporaryFiles.clear();clearCookies() }
    fun clearCookies() {synchronized(cookies) {cookies.clear()}}
    private fun file(name:String):File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,120}")) && name!="." && name!="..") {"文件名只能包含字母、数字、点、横线和下划线"}
        root.mkdirs()
        return File(root,name).canonicalFile.also {require(it.parentFile==root.canonicalFile) {"文件路径无效"}}
    }
    private fun values(value:Any?):List<String> = if(value is JSONArray) (0 until value.length()).map {value.get(it).toString()} else listOf(if(value==JSONObject.NULL) "" else value.toString())
    private fun timeout(o:JSONObject,key:String,default:Long):Long = o.optLong(key,if(lowMemory) default.coerceAtMost(15000) else default).also {require(it in 1..(if(lowMemory) 15000 else 120000)) {"$key 超出设备允许的超时范围"}}
    fun request(url:String,o:JSONObject):JSONObject {
        val method=o.optString("method","GET").uppercase(java.util.Locale.ROOT)
        require(method.matches(Regex("[A-Z]+")) && method!="CONNECT") {"不支持的请求方法"}
        val urlBuilder=url.toHttpUrl().newBuilder()
        o.optJSONObject("query")?.let {query->query.keys().forEach {key->values(query.get(key)).forEach {urlBuilder.addQueryParameter(key,it)}}}
        val builder=Request.Builder().url(urlBuilder.build())
        o.optJSONObject("headers")?.let {headers->headers.keys().forEach {key->values(headers.get(key)).forEach {builder.addHeader(key,it)}}}
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
        val client=base.newBuilder().callTimeout(callTimeout,TimeUnit.MILLISECONDS)
            .connectTimeout(timeout(o,"connectTimeout",10000),TimeUnit.MILLISECONDS)
            .readTimeout(timeout(o,"readTimeout",30000),TimeUnit.MILLISECONDS)
            .writeTimeout(timeout(o,"writeTimeout",30000),TimeUnit.MILLISECONDS)
            .followRedirects(o.optBoolean("followRedirects",true)).followSslRedirects(false)
            .cookieJar(if(o.optBoolean("cookies",true)) jar else CookieJar.NO_COOKIES).build()
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
                if(o.optBoolean("throwOnHttpError",false) && !response.isSuccessful) throw java.io.IOException("HTTP ${response.code} ${response.message}")
                if(o.has("filename")) require(!o.getString("filename").startsWith("andjs-temp-") && !o.getString("filename").startsWith("andjs-part-")) {"文件名使用了临时文件保留前缀"}
                val destination=if(responseType=="file") file(o.optString("filename","andjs-temp-"+UUID.randomUUID().toString()+".bin")).also {if(!o.has("filename")) temporaryFiles.add(it)} else null
                if(destination!=null) require(!destination.exists() || o.optBoolean("overwrite",false)) {"下载文件已存在"}
                val temp=destination?.let {File.createTempFile("andjs-part-",".tmp",root)}
                var count=0L
                try {
                    val memory=if(temp==null) ByteArrayOutputStream() else null
                    (temp?.outputStream() ?: memory!!).use {out->
                        response.body?.byteStream()?.use {input->
                            val bytes=ByteArray(8192)
                            while(true) {
                                if(control.stopped.get()) throw java.io.IOException("请求已停止")
                                val n=input.read(bytes);if(n<0) break
                                count+=n;require(count<=max) {"响应超过 maxBytes 限制"};out.write(bytes,0,n)
                            }
                        }
                    }
                    if(control.stopped.get()) throw java.io.IOException("请求已停止")
                    if(temp!=null) {
                        if(o.optBoolean("overwrite",false)) Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
                        else Files.move(temp.toPath(),destination.toPath())
                        result.put("file",destination.name).put("isFile",response.isSuccessful)
                    } else {
                        val bytes=memory!!.toByteArray()
                        if(responseType=="base64") result.put("base64",Base64.getEncoder().encodeToString(bytes))
                        else result.put("text",String(bytes,response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                    }
                    return result.put("size",count).put("elapsedMs",(System.nanoTime()-start)/1_000_000)
                } finally {temp?.delete()}
            }
        } finally {requestDns.end();release()}
    }
}

/** System DNS may ignore both socket cancellation and thread interruption. Bound the caller's
 * wait independently, and cap abandoned native lookups across all script sessions. */
private class InterruptibleDns(private val delegate:Dns,private val control:RunControl):Dns {
    companion object {
        private val workers=java.util.concurrent.ThreadPoolExecutor(
            0,2,30,TimeUnit.SECONDS,java.util.concurrent.SynchronousQueue<Runnable>(),
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
