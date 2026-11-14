package io.todu.andjs.runtime

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Device-local, bounded cache. No worker, alarm or background service. */
class ScriptCache(private val directory:File) {
    companion object {private val lock=Any();private const val MAX_BYTES=1_048_576}
    private val file=AtomicFile(File(directory,"values.json"))
    private fun load():JSONObject = if(!file.baseFile.exists()) JSONObject() else {
        require(file.baseFile.length()<=MAX_BYTES) {"缓存文件过大"}
        JSONObject(file.openRead().bufferedReader().use {it.readText()})
    }
    private fun save(data:JSONObject) {
        val bytes=data.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES) {"缓存总量不能超过 1MB"}
        directory.mkdirs()
        val stream=file.startWrite()
        try {stream.write(bytes);file.finishWrite(stream)} catch(e:Exception) {file.failWrite(stream);throw e}
    }
    private fun prune(data:JSONObject):Boolean {
        val now=System.currentTimeMillis()
        val expired=data.keys().asSequence().filter {data.getJSONObject(it).getLong("expires")<=now}.toList()
        expired.forEach {data.remove(it)}
        return expired.isNotEmpty()
    }
    fun clean()=synchronized(lock) {val data=load();if(prune(data)) save(data)}
    fun call(method:String,args:JSONArray):Any? = synchronized(lock) {
        val key=args.get(0)
        require(key is String && key.isNotBlank() && key.length<=200) {"缓存键须为 1–200 字符"}
        val data=load();val changed=prune(data)
        when(method) {
            "get"->{if(changed) save(data);plain(if(data.has(key)) data.getJSONObject(key).get("value") else args.opt(1))}
            "set"->{
                require(args.length()>1) {"缺少缓存值"}
                val ttl=if(args.length()>2) args.get(2) else 3600
                require(ttl is Number && ttl.toDouble().isFinite() && ttl.toDouble()>0 && ttl.toDouble()<=31_536_000) {"有效期须大于 0 且不超过 31536000 秒"}
                require(data.has(key) || data.length()<256) {"缓存最多保存 256 项"}
                data.put(key,JSONObject().put("value",args.get(1)).put("expires",System.currentTimeMillis()+(ttl.toDouble()*1000).toLong()))
                save(data);true
            }
            "remove"->{val removed=data.has(key);data.remove(key);if(changed || removed) save(data);removed}
            else->error("未知缓存方法")
        }
    }
    private fun plain(value:Any?):Any? = when(value) {
        null,JSONObject.NULL->null
        is JSONObject->value.keys().asSequence().associateWith {plain(value.get(it))}
        is JSONArray->(0 until value.length()).map {plain(value.get(it))}
        else->value
    }
}
