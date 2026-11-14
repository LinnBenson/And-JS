package io.todu.andjs.wear

import io.todu.andjs.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** One atomic file commits scripts, order and variables together. Runtime writes stay on this device. */
class WatchStore(directory:File) {
    private val file=File(directory,"watch-snapshot.json")
    val state=MutableStateFlow(WatchSnapshot())
    @Volatile var origin="";private set
    @Volatile var sequence=0L;private set
    @Volatile var hash="";private set
    var loadError:String?=null;private set
    init {synchronized(this) {
        directory.listFiles()?.filter {it.isFile && it.name.startsWith("snapshot-") && it.name.endsWith(".tmp") && System.currentTimeMillis()-it.lastModified()>3_600_000}?.forEach {it.delete()}

        try {if(file.exists()) {
            require(file.length()<=WatchSnapshot.MAX_BYTES*3L)
            val json=JSONObject(file.readText(Charsets.UTF_8))
            state.value=WatchSnapshot.decode(json.getString("snapshot"));origin=json.getString("origin");sequence=json.getLong("sequence");hash=json.getString("hash")
        }} catch(_:Exception) {loadError="无法读取手表脚本，请重新同步"}
    }}
    private fun save(snapshot:WatchSnapshot,from:String,version:Long,digest:String) {
        val text=snapshot.encode();WatchSnapshot.decode(text)
        val json=JSONObject().put("snapshot",text).put("origin",from).put("sequence",version).put("hash",digest)
        file.parentFile?.mkdirs()
        val temp=File.createTempFile("snapshot-",".tmp",file.parentFile)
        try {
            java.io.FileOutputStream(temp).use {it.write(json.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
            java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {temp.delete()}

        origin=from;sequence=version;hash=digest;loadError=null;state.value=snapshot
    }
    @Synchronized fun apply(text:String,from:String,version:Long,digest:String):Boolean {
        require(version>0)
        require(WatchSnapshot.hash(text)==digest) {"同步内容校验失败"}
        if(origin==from && version<sequence) return false
        if(origin==from && version==sequence) {require(hash==digest);return true}
        val snapshot=WatchSnapshot.decode(text)
        save(snapshot,from,version,digest);return true
    }
    @Synchronized fun runData(id:String):Triple<ScriptDocument,List<ScriptParameter>,Long>? {
        val snapshot=state.value
        return snapshot.scripts.find {it.item.id==id}?.let {Triple(it,snapshot.globals,sequence)}
    }
    @Synchronized fun writeVariables(scriptId:String,runSequence:Long,namespace:String,values:List<ScriptParameter>) {
        check(runSequence==sequence) {"脚本已更新，请重新运行"}
        val old=state.value
        val updated=if(namespace=="global") old.copy(globals=values) else {
            check(old.scripts.any {it.item.id==scriptId}) {"脚本已移除"}
            old.copy(scripts=old.scripts.map {if(it.item.id==scriptId) it.copy(item=it.item.copy(parameters=values)) else it})
        }
        save(updated,origin,sequence,hash)
    }
}
