package io.todu.andjs.data
import android.content.Context
import android.util.AtomicFile
import io.todu.andjs.domain.ScriptParameter
import io.todu.andjs.runtime.ParameterValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
class GlobalRepository(context: Context) {
    private val file=AtomicFile(File(context.filesDir,"globals.json"))
    suspend fun load():List<ScriptParameter> = withContext(Dispatchers.IO) {
        if(!file.baseFile.exists()) emptyList() else {
            val array=JSONArray(file.openRead().bufferedReader().use {it.readText()})
            (0 until array.length()).map {val p=array.getJSONObject(it);ScriptParameter(p.getString("key"),p.getString("type"),p.getString("value"))}
        }
    }
    suspend fun save(parameters:List<ScriptParameter>) = withContext(Dispatchers.IO) {
        require(ParameterValues.error(parameters)==null)
        val array=JSONArray().apply {parameters.forEach {put(JSONObject().put("key",it.key).put("type",it.type).put("value",it.value))}}
        val stream=file.startWrite()
        try {stream.write(array.toString(2).toByteArray(Charsets.UTF_8));file.finishWrite(stream)} catch(e:Exception) {file.failWrite(stream);throw e}
    }
}
