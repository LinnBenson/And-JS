package io.todu.andjs.runtime

interface PlatformApi {
    fun openDeviceSession(control:RunControl):DeviceSession = throw UnsupportedOperationException("Device listeners unavailable")
    fun openBle(control:RunControl):BleSession = throw UnsupportedOperationException("BLE unavailable")
    fun call(namespace:String,method:String,args:org.json.JSONArray):Any?
    fun call(namespace:String,method:String,args:org.json.JSONArray,control:RunControl):Any? = call(namespace,method,args)
}
