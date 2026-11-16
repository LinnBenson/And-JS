package io.todu.andjs.runtime

import org.json.JSONArray
import org.json.JSONObject

interface DeviceSession:AutoCloseable {
    fun open(kind:String,options:JSONObject):BleHandle
    fun status(kind:String):JSONObject
}
class DeviceFailure(val details:JSONObject):Exception(details.optString("message"))
internal fun deviceError(code:String,message:String)=DeviceFailure(JSONObject().put("name","DeviceError").put("code",code).put("message",message))
