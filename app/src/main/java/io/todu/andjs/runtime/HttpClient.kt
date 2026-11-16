package io.todu.andjs.runtime

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException

/** Native networking only: Rhino values and callbacks never enter worker threads. */
internal class HttpClient(private val session:HttpSession,private val control:RunControl,
    private val lowMemory:Boolean=false,defaults:JSONObject=JSONObject()) {
    private val defaults=JSONObject(defaults.toString())
    private val baseUrl=if(defaults.has("baseUrl")) defaults.getString("baseUrl").toHttpUrl() else null
    init {
        require(!defaults.has("url")) {"客户端基础地址请使用 baseUrl"}
    }
    fun responseType(options:JSONObject)=merge(options).optString("responseType","auto")
    fun clearCookies()=session.clearCookies()
    fun setCookies(url:String,value:Any)=session.setCookies(resolve(url),value)
    fun getCookies(url:String):JSONArray=session.getCookies(resolve(url))
    private fun resolve(url:String):String {
        val resolved=if(baseUrl==null) url.toHttpUrl() else baseUrl.resolve(url) ?: error("URL 无效")
        require(baseUrl==null || (resolved.scheme==baseUrl.scheme && resolved.host==baseUrl.host && resolved.port==baseUrl.port)) {"客户端请求必须与 baseUrl 同源"}
        require(resolved.username.isEmpty() && resolved.password.isEmpty()) {"URL 不应包含账号密码，请使用 auth"}
        return resolved.toString()
    }
    private fun merge(options:JSONObject):JSONObject {
        val merged=JSONObject(defaults.toString())
        val bodyKeys=listOf("data","json","form","text","base64","multipart","file")
        if(bodyKeys.any {options.has(it)}) bodyKeys.forEach {merged.remove(it)}
        // Header names are case insensitive. A null entry removes an inherited header/query.
        options.keys().forEach {key->
            val value=options.get(key)
            if(key in listOf("headers","query") && value is JSONObject) {
                val target=JSONObject(merged.optJSONObject(key)?.toString() ?: "{}")
                value.keys().forEach {name->
                    if(key=="headers") target.keys().asSequence().toList().filter {it.equals(name,true)}.forEach {target.remove(it)}
                    if(value.isNull(name)) target.remove(name) else target.put(name,value.get(name))
                }
                merged.put(key,target)
            } else if(value==JSONObject.NULL && key !in listOf("data","json")) merged.remove(key) else merged.put(key,value)
        }
        merged.remove("baseUrl")
        return merged
    }
    private fun integerOption(options:JSONObject,key:String,default:Long,min:Long,max:Long):Long {
        if(!options.has(key)) return default
        val number=options.get(key) as? Number ?: throw IllegalArgumentException("$key 须为整数")
        val n=number.toDouble()
        require(n.isFinite() && n%1==0.0 && n>=min && n<=max) {"$key 超出范围"}
        return n.toLong()
    }
    private data class Retry(val count:Int,val delay:Long,val maxDelay:Long,val methods:Set<String>,val statuses:Set<Int>)
    private fun retry(options:JSONObject):Retry {
        val value=options.opt("retry")
        val config=when(value) {
            null,JSONObject.NULL -> JSONObject()
            is Number -> JSONObject().put("count",value)
            is JSONObject -> value
            else -> throw IllegalArgumentException("retry 须为重试次数或配置对象")
        }
        fun integer(key:String,default:Long,min:Long,max:Long):Long {
            if(!config.has(key)) return default
            val number=config.get(key) as? Number ?: throw IllegalArgumentException("retry.$key 须为整数")
            val n=number.toDouble();require(n.isFinite() && n%1==0.0 && n>=min && n<=max) {"retry.$key 超出范围"}
            return n.toLong()
        }
        val methods=if(config.has("methods")) {
            val list=config.getJSONArray("methods");require(list.length() in 1..12) {"retry.methods 须为 1–12 项"}
            (0 until list.length()).map {list.getString(it).uppercase(Locale.ROOT).also {m->require(m.matches(Regex("[A-Z]+")) && m!="CONNECT") {"retry.methods 无效"}}}.toSet()
        } else setOf("GET","HEAD","OPTIONS")
        val statuses=if(config.has("statuses")) {
            val list=config.getJSONArray("statuses");require(list.length()<=20) {"retry.statuses 最多 20 项"}
            (0 until list.length()).map {val n=list.get(it) as? Number ?: throw IllegalArgumentException("retry.statuses 须为状态码数组");require(n.toDouble()%1==0.0 && n.toInt() in 400..599);n.toInt()}.toSet()
        } else setOf(408,429,500,502,503,504)
        val delay=integer("delayMs",500,0,10000)
        val maxDelay=integer("maxDelayMs",5000,0,30000)
        require(maxDelay>=delay) {"retry.maxDelayMs 不得小于 delayMs"}
        return Retry(integer("count",0,0,if(lowMemory) 2 else 5).toInt(),delay,maxDelay,methods,statuses)
    }
    private fun stopped() {
        if(control.stopped.get() || Thread.currentThread().isInterrupted) throw HttpFailure(JSONObject()
            .put("name","HttpError").put("code","CANCELLED").put("message","请求已停止").put("retryable",false))
    }
    fun request(url:String,options:JSONObject,inheritDefaults:Boolean=true):JSONObject {
        val started=System.nanoTime()
        var address=url
        var method=options.optString("method",defaults.optString("method","GET")).uppercase(Locale.ROOT)
        var attempts=0
        try {
            stopped()
            address=resolve(url)
            val merged=if(inheritDefaults) merge(options) else JSONObject(options.toString())
            method=merged.optString("method","GET").uppercase(Locale.ROOT)
            val policy=retry(merged)
            val budget=integerOption(merged,"totalTimeout",if(lowMemory) 15000 else 120000,1,if(lowMemory) 15000 else 120000)
            require(budget in 1..(if(lowMemory) 15000 else 120000)) {"totalTimeout 超出设备允许范围"}
            val deadline=started+TimeUnit.MILLISECONDS.toNanos(budget)
            var lastFailure:HttpFailure?=null
            while(true) {
                stopped()
                val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
                if(remaining<=0) throw java.net.SocketTimeoutException()
                val attemptOptions=JSONObject(merged.toString())
                val perAttempt=integerOption(merged,"timeout",if(lowMemory) 15000 else 30000,1,if(lowMemory) 15000 else 120000)
                require(perAttempt in 1..(if(lowMemory) 15000 else 120000)) {"timeout 超出设备允许范围"}
                attemptOptions.put("timeout",minOf(perAttempt,remaining)).put("throwOnHttpError",false)
                attempts++
                var response:JSONObject?=null
                try {response=session.request(address,attemptOptions)} catch(e:Exception) {
                    stopped()
                    lastFailure=httpFailure(e,address,method,attempts=attempts)
                }
                val retryable=if(response!=null) response.getInt("status") in policy.statuses else lastFailure!!.details.optBoolean("retryable")
                if(!retryable || attempts>policy.count || method !in policy.methods) {
                    if(response==null) throw lastFailure!!
                    response.put("method",method).put("attempts",attempts).put("elapsedMs",(System.nanoTime()-started)/1_000_000)
                    if(merged.optBoolean("throwOnHttpError",false) && !response.getBoolean("ok")) throw httpStatusFailure(response,method)
                    return response
                }
                val exponential=(policy.delay*(1L shl (attempts-1))).coerceAtMost(policy.maxDelay)
                val retryAfter=response?.optJSONObject("headers")?.optJSONArray("retry-after")?.optString(0)?.let {retryAfterMillis(it)}
                val delay=maxOf(exponential,retryAfter ?: 0L)
                if(delay>policy.maxDelay || TimeUnit.MILLISECONDS.toNanos(delay)>=deadline-System.nanoTime()) {
                    if(response==null) throw lastFailure!!
                    response.put("method",method).put("attempts",attempts).put("elapsedMs",(System.nanoTime()-started)/1_000_000)
                    if(merged.optBoolean("throwOnHttpError",false) && !response.getBoolean("ok")) throw httpStatusFailure(response,method)
                    return response
                }
                val wake=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(delay)
                while(System.nanoTime()<wake) {stopped();Thread.sleep(minOf(50L,TimeUnit.NANOSECONDS.toMillis(wake-System.nanoTime()).coerceAtLeast(1)))}
            }
        } catch(e:Exception) {
            val failure=if(control.stopped.get() || Thread.currentThread().isInterrupted) HttpFailure(JSONObject().put("name","HttpError").put("code","CANCELLED").put("message","请求已停止").put("retryable",false)) else httpFailure(e,address,method)
            failure.details.put("url",address).put("method",method).put("attempts",attempts).put("elapsedMs",(System.nanoTime()-started)/1_000_000)
            if(!failure.details.has("status")) failure.details.put("status",0)
            if(!failure.details.has("phase")) failure.details.put("phase","request")
            throw failure
        }
    }
    private fun retryAfterMillis(value:String):Long? {
        value.trim().toLongOrNull()?.let {return it.coerceIn(0,86400)*1000}
        return runCatching {
            val date=java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            (date.toInstant().toEpochMilli()-System.currentTimeMillis()).coerceAtLeast(0)
        }.getOrNull()
    }
    fun batch(requests:JSONArray,options:JSONObject):JSONArray {
        require(requests.length() in 1..(if(lowMemory) 8 else 20)) {"batch 请求数量超出设备限制"}
        val concurrency=integerOption(options,"concurrency",if(lowMemory) 2 else 4,1,if(lowMemory) 2 else 4).toInt()
        require(concurrency in 1..(if(lowMemory) 2 else 4)) {"concurrency 超出设备限制"}
        val prepared=(0 until requests.length()).map {index->
            val item=requests.getJSONObject(index)
            val url=item.getString("url")
            resolve(url)
            val requestOptions=JSONObject(item.toString()).apply {remove("url")}
            url to merge(requestOptions)
        }
        val filenames=prepared.mapNotNull {(_,o)->if(o.optString("responseType")=="file" && o.has("filename")) o.getString("filename") else null}
        require(filenames.distinct().size==filenames.size) {"同批下载不能使用相同 filename"}
        val memoryCount=prepared.count {it.second.optString("responseType")!="file"}.coerceAtLeast(1)
        val fileCount=prepared.count {it.second.optString("responseType")=="file"}.coerceAtLeast(1)
        val memoryBudget=if(lowMemory) 1024L*1024 else 8L*1024*1024
        val fileBudget=if(lowMemory) 16L*1024*1024 else 256L*1024*1024
        val fileErrorLimit=if(lowMemory) 65536L else 262144L
        val reservedErrors=prepared.count {it.second.optString("responseType")=="file"}*fileErrorLimit
        var memoryBytes=reservedErrors;var fileBytes=0L
        prepared.forEach {(_,o)->
            val file=o.optString("responseType")=="file"
            val cap=if(file) fileBudget/fileCount else (memoryBudget-reservedErrors)/memoryCount
            val max=integerOption(o,"maxBytes",cap,1,if(file) fileBudget else memoryBudget)
            require(max in 1..(if(file) fileBudget else memoryBudget)) {"batch maxBytes 超出范围"}
            o.put("maxBytes",max)
            if(file) o.put("_errorMaxBytes",fileErrorLimit)
            if(file) fileBytes+=max else memoryBytes+=max
        }
        require(memoryBytes<=memoryBudget && fileBytes<=fileBudget) {"batch 响应总上限过大，请减少请求数量或 maxBytes"}
        val totalTimeout=integerOption(options,"totalTimeout",if(lowMemory) 15000 else 120000,1,if(lowMemory) 15000 else 120000)
        require(totalTimeout in 1..(if(lowMemory) 15000 else 120000)) {"batch totalTimeout 超出设备允许范围"}
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(totalTimeout)
        val pool=Executors.newFixedThreadPool(minOf(concurrency,requests.length())) {task->Thread(task,"AndJS-HTTP").apply {isDaemon=true}}
        val futures=mutableListOf<java.util.concurrent.Future<JSONObject>>()
        var complete=false
        val unregister=control.registerStop {pool.shutdownNow()}
        try {
            prepared.forEach {(url,o)->
                stopped()
                futures.add(pool.submit<JSONObject> {
                    try {
                        val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
                        if(remaining<=0) throw java.net.SocketTimeoutException()
                        o.put("totalTimeout",minOf(integerOption(o,"totalTimeout",totalTimeout,1,if(lowMemory) 15000 else 120000),remaining))
                        val response=request(url,o,false)
                        JSONObject().put("ok",response.getBoolean("ok")).put("response",response)
                            .put("_responseType",o.optString("responseType","auto")).apply {
                                if(!response.getBoolean("ok")) put("error",httpStatusFailure(response,o.optString("method","GET")).details)
                            }
                    } catch(e:Exception) {
                        val error=httpFailure(e,url,o.optString("method","GET")).details
                        JSONObject().put("ok",false).put("error",error).apply {error.optJSONObject("response")?.let {put("response",it)}}
                    }
                })
            }
            val results=JSONArray()
            futures.forEach {future->
                while(true) {
                    stopped()
                    if(System.nanoTime()>=deadline) throw java.net.SocketTimeoutException("批量请求总超时")
                    try {results.put(future.get(50,TimeUnit.MILLISECONDS));break} catch(_:TimeoutException) { }
                }
            }
            complete=true
            return results
        } catch(e:ExecutionException) {throw (e.cause ?: e)}
        finally {
            unregister()
            if(!complete) {futures.forEach {it.cancel(true)};session.cancelRequests()}
            pool.shutdownNow()
            try {pool.awaitTermination(1000,TimeUnit.MILLISECONDS)} catch(_:InterruptedException) {Thread.currentThread().interrupt()}
        }
    }
}
