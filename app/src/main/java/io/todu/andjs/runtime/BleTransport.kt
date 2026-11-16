package io.todu.andjs.runtime

import org.json.JSONArray
import org.json.JSONObject

interface BleSession:AutoCloseable { fun open(kind:String,args:JSONArray):BleHandle }
interface BleHandle:AutoCloseable {
    fun call(method:String,args:JSONArray):Any?
    fun poll(timeoutMs:Long):JSONObject?
    fun info():JSONObject
}
class BleFailure(val details:JSONObject):Exception(details.optString("message"))
internal fun bleError(code:String,message:String,status:Int=0)=BleFailure(JSONObject().put("name","BleError").put("code",code).put("message",message).put("status",status))
