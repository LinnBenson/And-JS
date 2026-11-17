package io.todu.andjs.domain

data class Script(val id: String, val name: String, val category: String, val icon: String, val parameters: List<ScriptParameter> = emptyList(), val color: String = "mint", val widget: Boolean = false)
data class ScriptDocument(val item: Script, val source: String)
interface ScriptRepository {
    suspend fun <T> transaction(block:suspend ()->T):T = block()
    suspend fun load(): List<Script>
    suspend fun read(id: String): String
    suspend fun save(document: ScriptDocument)
    suspend fun saveOrder(ids: List<String>)
    suspend fun delete(id: String)
}

data class ScriptParameter(val key: String, val type: String = "string", val value: String = "")

data class ScriptCategory(val id:String,val name:String)
