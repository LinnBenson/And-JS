package io.todu.andjs.runtime

import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal class HttpFailure(val details:JSONObject,cause:Throwable?=null):IOException(details.optString("message"),cause)

internal fun httpFailure(error:Exception,url:String,method:String,phase:String="request",attempts:Int=0,elapsedMs:Long=0):HttpFailure {
    if(error is HttpFailure) return error
    val code=when(error) {
        is SocketTimeoutException -> "TIMEOUT"
        is UnknownHostException -> "DNS_ERROR"
        is SSLException -> "TLS_ERROR"
        is ConnectException -> "CONNECTION_ERROR"
        is InterruptedIOException -> if(Thread.currentThread().isInterrupted) "CANCELLED" else "TIMEOUT"
        is IllegalArgumentException, is org.json.JSONException -> "INVALID_OPTIONS"
        is IOException -> if(phase=="file") "FILE_ERROR" else "NETWORK_ERROR"
        else -> "REQUEST_ERROR"
    }
    val message=when(code) {
        "TIMEOUT" -> "网络请求超时"
        "DNS_ERROR" -> "域名解析失败或繁忙"
        "TLS_ERROR" -> "安全连接或证书验证失败"
        "CONNECTION_ERROR" -> "无法连接服务器"
        "CANCELLED" -> "请求已停止"
        "INVALID_OPTIONS" -> error.message ?: "请求参数无效"
        "FILE_ERROR" -> "文件读写失败"
        else -> "网络请求失败"
    }
    return HttpFailure(JSONObject().put("name","HttpError").put("code",code).put("message",message)
        .put("url",url).put("method",method).put("phase",phase).put("status",0)
        .put("attempts",attempts).put("elapsedMs",elapsedMs)
        .put("retryable",code in listOf("TIMEOUT","DNS_ERROR","CONNECTION_ERROR","NETWORK_ERROR")),error)
}

internal fun httpStatusFailure(response:JSONObject,method:String):HttpFailure = HttpFailure(JSONObject()
    .put("name","HttpError").put("code","HTTP_ERROR").put("message","HTTP ${response.getInt("status")}")
    .put("url",response.optString("url")).put("method",method).put("phase","response")
    .put("status",response.getInt("status")).put("response",response)
    .put("attempts",response.optInt("attempts",1)).put("elapsedMs",response.optLong("elapsedMs"))
    .put("retryable",response.getInt("status") in listOf(408,429,500,502,503,504)))
