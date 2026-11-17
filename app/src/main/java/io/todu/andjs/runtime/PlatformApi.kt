package io.todu.andjs.runtime

interface PlatformApi {
    fun openHttpServer(port:Int,token:String,maxFileSizeMB:Int,control:RunControl):HttpServerSession = throw UnsupportedOperationException("当前环境不支持 HTTP 服务")
    fun openDeviceSession(control:RunControl):DeviceSession = throw UnsupportedOperationException("Device listeners unavailable")
    fun openBle(control:RunControl):BleSession = throw UnsupportedOperationException("BLE unavailable")
    fun call(namespace:String,method:String,args:org.json.JSONArray):Any?
    fun call(namespace:String,method:String,args:org.json.JSONArray,control:RunControl):Any? = call(namespace,method,args)
}
