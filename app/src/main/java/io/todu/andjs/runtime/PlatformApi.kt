package io.todu.andjs.runtime

interface PlatformApi {
    fun call(namespace:String,method:String,args:org.json.JSONArray):Any?
    fun call(namespace:String,method:String,args:org.json.JSONArray,control:RunControl):Any? = call(namespace,method,args)
}
