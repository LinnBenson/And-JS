package io.todu.andjs.runtime

import io.todu.andjs.domain.ScriptParameter
import org.json.JSONArray
import org.json.JSONObject

/** Host-side value helpers for the JS binding: andjs.value.get(key, defaultValue) / andjs.global.get(key, defaultValue). */
class AndJsValues(parameters: List<ScriptParameter>) {
    private val values = parameters.associate { it.key to ParameterValues.parse(it) }
    fun get(key: String, defaultValue: Any? = null): Any? = if(values.containsKey(key)) values[key] else defaultValue
}
object ParameterValues {
    fun parse(parameter: ScriptParameter): Any = when(parameter.type) {
        "boolean" -> { require(parameter.value=="true" || parameter.value=="false"); parameter.value=="true" }
        "string" -> parameter.value
        "number" -> parameter.value.toDouble().also { require(it.isFinite()) }
        "array" -> convertArray(JSONArray(parameter.value))
        else -> error("不支持的参数类型")
    }
    private fun convertArray(array: JSONArray): List<Any?> = (0 until array.length()).map { convert(array.get(it)) }
    private fun convert(value: Any?): Any? = when(value) {
        JSONObject.NULL -> null
        is JSONArray -> convertArray(value)
        is JSONObject -> value.keys().asSequence().associateWith { convert(value.get(it)) }
        else -> value
    }
    fun error(parameters: List<ScriptParameter>): String? {
        if(parameters.any {it.key.isBlank()}) return "参数键不能为空"
        if(parameters.map {it.key}.distinct().size!=parameters.size) return "参数键不能重复"
        parameters.forEach { p -> try { parse(p) } catch(e: Exception) { return "${p.key}：${if(p.type=="array") "请输入有效的 JSON 数组" else "参数值无效"}" } }
        return null
    }
}

/** A fresh immutable value snapshot is created for each script run. */
class AndJsApi(script: List<ScriptParameter>, globals: List<ScriptParameter>) {
    val value = AndJsValues(script)
    val global = AndJsValues(globals)
}
