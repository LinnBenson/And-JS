package io.todu.andjs.wear

import io.todu.andjs.domain.*
import io.todu.andjs.runtime.ParameterValues
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class WatchSnapshot(val scripts:List<ScriptDocument> = emptyList(),val globals:List<ScriptParameter> = emptyList()) {
    fun encode():String = JSONObject().put("schema",1).put("scripts",JSONArray().apply {
        scripts.forEach {d->put(JSONObject().put("id",d.item.id).put("name",d.item.name).put("icon",d.item.icon).put("color",d.item.color)
            .put("widget",d.item.widget).put("source",d.source).put("parameters",parameters(d.item.parameters)))}
    }).put("globals",parameters(globals)).toString()
    companion object {
        const val MAX_BYTES=2*1024*1024
        fun hash(text:String):String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
        private fun parameters(values:List<ScriptParameter>)=JSONArray().apply {values.forEach {put(JSONObject().put("key",it.key).put("type",it.type).put("value",it.value))}}
        private fun decodeParameters(array:JSONArray):List<ScriptParameter> {
            require(array.length()<=1000)
            return (0 until array.length()).map {i->val p=array.getJSONObject(i);ScriptParameter(p.getString("key"),p.getString("type"),p.getString("value"))}.also {require(ParameterValues.error(it)==null)}
        }
        fun decode(text:String):WatchSnapshot {
            require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) {"同步内容超过 2MB"}
            val json=JSONObject(text);require(json.getInt("schema")==1) {"请更新手机和手表应用"}
            val items=json.getJSONArray("scripts");require(items.length()<=100) {"最多同步 100 个脚本"}
            val docs=(0 until items.length()).map {i->
                val d=items.getJSONObject(i);val id=d.getString("id");val name=d.getString("name");val source=d.getString("source")
                require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")) && name.isNotBlank() && name.length<=40 && source.length<=256_000)
                ScriptDocument(Script(id,name,"",d.getString("icon"),decodeParameters(d.getJSONArray("parameters")),d.getString("color"),d.optBoolean("widget",false)),source)
            }
            require(docs.map {it.item.id}.distinct().size==docs.size)
            return WatchSnapshot(docs,decodeParameters(json.getJSONArray("globals")))
        }
    }
}
