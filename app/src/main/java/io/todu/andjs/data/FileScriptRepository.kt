package io.todu.andjs.data

import android.content.Context
import android.util.AtomicFile
import io.todu.andjs.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Each atomic document is authoritative; main.js is an exported executable mirror. */
class FileScriptRepository(private val context: Context) : ScriptRepository {
    private class TransactionContext:AbstractCoroutineContextElement(Key) {
        companion object Key:CoroutineContext.Key<TransactionContext>
    }
    companion object {private val transactionMutex=kotlinx.coroutines.sync.Mutex()}
    override suspend fun <T> transaction(block:suspend ()->T):T =
        if(currentCoroutineContext()[TransactionContext]!=null) block()
        else transactionMutex.withLock {withContext(TransactionContext()) {block()}}
    private suspend fun <T> io(block:suspend ()->T):T = transaction {withContext(Dispatchers.IO) {block()}}
    private val seedLock = kotlinx.coroutines.sync.Mutex()
    private val preferences = context.getSharedPreferences("examples", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "scripts").apply { mkdirs() }
    private fun folder(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        return File(root, id)
    }
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    override suspend fun load(): List<Script> = io {
        seedLock.lock()
        try {
            if(!preferences.getBoolean("removed-bundled-v9",false)) {
                val ids=listOf("example-hello","example-choice","example-password","example-time","example-text","example-stats","example-test-live","example-test-lines","example-test-empty","example-test-exit","example-test-error","example-test-stop","example-ui-input","example-ui-select","example-ui-multi","example-ui-confirm","example-device-info-v7","example-device-network-v7","example-device-location-v7","example-health-today-v7","example-health-current-v7","example-device-vibration-v7","example-image-offline-v7","example-image-network-v7")
                ids.forEach {id->val dir=folder(id);check(!dir.exists() || dir.deleteRecursively()) {"清理示例失败"}}
                check(preferences.edit().putBoolean("removed-bundled-v9",true).commit())
            }
        } finally {seedLock.unlock()}
        val orderFile=File(root,"order.json")
        val order=if(orderFile.exists()) JSONArray(AtomicFile(orderFile).openRead().bufferedReader().use {it.readText()}) else JSONArray()
        val ranks=(0 until order.length()).associate {order.getString(it) to it}
        root.listFiles().orEmpty().filter { it.isDirectory && File(it, "item.json").exists() }.map { dir ->
            val json = JSONObject(AtomicFile(File(dir, "item.json")).openRead().bufferedReader().use { it.readText() })
            Script(dir.name, json.getString("name"), json.getString("category"), json.getString("icon"), json.optJSONArray("parameters")?.let { array ->
                (0 until array.length()).map { i -> val p=array.getJSONObject(i); ScriptParameter(p.getString("key"),p.getString("type"),p.getString("value")) }
            }.orEmpty(), json.optString("color", "mint"),json.optBoolean("widget",false))
        }.sortedWith(compareBy<Script> {ranks[it.id] ?: Int.MAX_VALUE}.thenBy {it.name})
    }
    override suspend fun read(id: String): String = io {
        JSONObject(AtomicFile(File(folder(id), "item.json")).openRead().bufferedReader().use { it.readText() }).getString("source")
    }
    override suspend fun save(document: ScriptDocument) = io {
        val item = document.item
        require(item.name.isNotBlank() && item.name.length <= 40)
        require(io.todu.andjs.runtime.ParameterValues.error(item.parameters) == null)
        val dir = folder(item.id).apply { mkdirs() }
        val json = JSONObject().put("schemaVersion", 1).put("name", item.name).put("category", item.category)
            .put("widget",item.widget).put("icon", item.icon).put("color",item.color).put("source", document.source)
            .put("parameters", JSONArray().apply { item.parameters.forEach { put(JSONObject().put("key",it.key).put("type",it.type).put("value",it.value)) } })
        write(File(dir, "main.js"), document.source)
        write(File(dir, "item.json"), json.toString(2))
        io.todu.andjs.widget.ScriptWidgetProvider.refresh(context)
    }
    override suspend fun saveOrder(ids:List<String>):Unit = io {
        write(File(root,"order.json"),JSONArray(ids).toString())
        io.todu.andjs.widget.ScriptWidgetProvider.refresh(context)
    }
    override suspend fun delete(id: String): Unit = io {
        check(folder(id).deleteRecursively()) { "删除失败" }
        io.todu.andjs.widget.ScriptWidgetProvider.refresh(context)
    }
}
