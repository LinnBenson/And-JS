package io.todu.andjs.runtime

import io.todu.andjs.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** The UI keeps its editor closed while this session owns remote edits. */
internal class LanScriptEditor(private val repository:ScriptRepository,private val categories:List<ScriptCategory>,private val onSaved:()->Unit) {
    private fun encode(doc:ScriptDocument):JSONObject = JSONObject().put("id",doc.item.id).put("name",doc.item.name)
        .put("categoryId",doc.item.category).put("category",categories.find {it.id==doc.item.category}?.name ?: "未分类")
        .put("code",doc.source).put("icon",doc.item.icon).put("color",doc.item.color).put("widget",doc.item.widget)
        .put("params",JSONArray().apply {doc.item.parameters.forEach {put(JSONObject().put("key",it.key).put("type",it.type).put("value",it.value))}})
    private fun revision(doc:ScriptDocument):String=MessageDigest.getInstance("SHA-256").digest(encode(doc).toString().toByteArray()).joinToString("") {"%02x".format(it)}
    private fun entry(doc:ScriptDocument)=encode(doc).put("revision",revision(doc))
    @Synchronized fun handle(path:String,body:JSONObject?):JSONObject=runBlocking { repository.transaction {
        val items=repository.load()
        if(path=="/scripts") return@transaction JSONObject().put("scripts",JSONArray().apply {items.forEach {put(entry(ScriptDocument(it,repository.read(it.id))))}})
        val data=requireNotNull(body)
        val id=data.getString("id")
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) {"脚本 ID 无效"}
        val existing=items.find {it.id==id}
        if(existing==null) check(data.optString("revision").isEmpty()) {"脚本已删除，请刷新列表"}
        else check(data.optString("revision")==revision(ScriptDocument(existing,repository.read(id)))) {"脚本已被修改，请重新打开后再保存"}
        val name=data.getString("name").trim()
        require(name.isNotEmpty() && name.length<=40) {"脚本名称须为 1–40 字符"}
        val params=data.getJSONArray("params")
        val values=(0 until params.length()).map {i->val p=params.getJSONObject(i);ScriptParameter(p.getString("key").trim(),p.getString("type"),p.getString("value"))}
        ParameterValues.error(values)?.let {throw IllegalArgumentException(it)}
        val item=existing?.copy(name=name,parameters=values) ?: Script(id,name,"","code",values)
        val doc=ScriptDocument(item,data.getString("code"))
        repository.save(doc)
        onSaved()
        JSONObject().put("script",entry(doc))
    }}
}
