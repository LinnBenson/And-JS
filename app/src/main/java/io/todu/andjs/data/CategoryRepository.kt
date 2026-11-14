package io.todu.andjs.data
import android.content.Context
import android.util.AtomicFile
import io.todu.andjs.domain.ScriptCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
class CategoryRepository(context:Context) {
    private val file=AtomicFile(File(context.filesDir,"categories.json"))
    suspend fun load():List<ScriptCategory> = withContext(Dispatchers.IO) {
        if(!file.baseFile.exists()) listOf(ScriptCategory("生活","生活"),ScriptCategory("工具","工具")) else {
            val array=JSONArray(file.openRead().bufferedReader().use {it.readText()})
            (0 until array.length()).map {val item=array.getJSONObject(it);ScriptCategory(item.getString("id"),item.getString("name"))}
        }
    }
    suspend fun save(categories:List<ScriptCategory>) = withContext(Dispatchers.IO) {
        require(categories.all {it.name.isNotBlank() && it.name.length<=20})
        require(categories.map {it.name}.distinct().size==categories.size)
        val array=JSONArray().apply {categories.forEach {put(JSONObject().put("id",it.id).put("name",it.name))}}
        val stream=file.startWrite()
        try {stream.write(array.toString(2).toByteArray());file.finishWrite(stream)} catch(e:Exception){file.failWrite(stream);throw e}
    }
}
