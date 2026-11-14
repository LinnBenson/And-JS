package io.todu.andjs.wear

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import io.todu.andjs.AndJsApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.TimeUnit

fun <T> Task<T>.blocking():T=Tasks.await(this,30,TimeUnit.SECONDS)
class WatchSync(private val context:Context) {
    private val prefs=context.getSharedPreferences("watch-sync",Context.MODE_PRIVATE)
    val revision=MutableStateFlow(0L)
    @Synchronized fun selection():List<String> {
        val a=JSONArray(prefs.getString("selection","[]"));return (0 until a.length()).map {a.getString(it)}
    }
    @Synchronized fun select(ids:List<String>) {check(prefs.edit().putString("selection",JSONArray(ids.distinct()).toString()).commit());revision.value++}
    fun widgetIds():Set<String> = prefs.getStringSet("widgets",emptySet())!!.toSet()
    fun setWidget(id:String,enabled:Boolean) {val ids=widgetIds().toMutableSet();if(enabled) ids.add(id) else ids.remove(id);check(prefs.edit().putStringSet("widgets",ids).commit());revision.value++}
    fun acknowledged():String=prefs.getString("ack",WatchSnapshot.hash(WatchSnapshot().encode()))!!
    suspend fun send(text:String):String=withContext(Dispatchers.IO) {
        require(text.toByteArray().size<=WatchSnapshot.MAX_BYTES) {"同步内容超过 2MB"}
        WatchSnapshot.decode(text)
        val nodes=Wearable.getCapabilityClient(context).getCapability("andjs_watch",CapabilityClient.FILTER_REACHABLE).blocking().nodes
        check(nodes.isNotEmpty()) {"未连接手表，请确认手表已安装 And JS"}
        check(nodes.size==1) {"请只连接一块手表后同步"}
        val target=nodes.first().id
        val request=PutDataMapRequest.create("/andjs/snapshot/$target")
        val token=UUID.randomUUID().toString();val digest=WatchSnapshot.hash(text)
        val sequence=synchronized(this@WatchSync) {
            val seq=prefs.getLong("sequence",0)+1
            check(prefs.edit().putLong("sequence",seq).putString("pending",token).putString("pendingHash",digest).putString("target",target).commit());seq
        }
        request.dataMap.apply {
            putAsset("snapshot",Asset.createFromBytes(text.toByteArray(Charsets.UTF_8)))
            putString("token",token);putString("hash",digest);putLong("sequence",sequence);putString("target",target)
        }
        Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).blocking()
        token
    }
    @Synchronized fun acceptAck(node:String,map:DataMap) {
        if(node!=prefs.getString("target",null) || map.getString("token")!=prefs.getString("pending",null) || map.getString("hash")!=prefs.getString("pendingHash",null)) return
        check(prefs.edit().putString("ack",map.getString("hash")).putLong("ackSequence",map.getLong("sequence")).remove("pending").commit());revision.value++
    }
    fun isPending(token:String)=prefs.getString("pending",null)==token
    @Synchronized fun acceptStatus(node:String,map:DataMap) {
        if(node==prefs.getString("target",null) && map.getString("hash")!=acknowledged() && (map.getBoolean("reset") || map.getLong("sequence")>=prefs.getLong("ackSequence",0))) {
            check(prefs.edit().putString("ack","").commit());revision.value++
        }
    }
    suspend fun recover()=withContext(Dispatchers.IO) {
        val buffer=Wearable.getDataClient(context).dataItems.blocking()
        try {buffer.forEach {item->if(item.uri.path?.startsWith("/andjs/ack/")==true) acceptAck(item.uri.host.orEmpty(),DataMapItem.fromDataItem(item).dataMap)}} finally {buffer.release()}
        val nodes=Wearable.getCapabilityClient(context).getCapability("andjs_watch",CapabilityClient.FILTER_REACHABLE).blocking().nodes
        if(nodes.size==1 && prefs.contains("target") && nodes.first().id!=prefs.getString("target",null)) {
            check(prefs.edit().putString("ack","").commit());revision.value++
        }
    }
}

class WatchSyncService:WearableListenerService() {
    override fun onDataChanged(events:DataEventBuffer) {
        val app=application as AndJsApplication
        val isWatch=packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        for(event in events) if(event.type==DataEvent.TYPE_CHANGED) {
            val item=event.dataItem
            try {
                val map=DataMapItem.fromDataItem(item).dataMap
                if(!isWatch && item.uri.path?.startsWith("/andjs/status/")==true) app.watchSync.acceptStatus(item.uri.host.orEmpty(),map)
                if(!isWatch && item.uri.path?.startsWith("/andjs/ack/")==true) app.watchSync.acceptAck(item.uri.host.orEmpty(),map)
                if(isWatch && item.uri.path?.startsWith("/andjs/snapshot/")==true) receive(app,item,map)
            } catch(e:Exception) {android.util.Log.w("AndJS","Watch sync failed",e)}
        }
    }
    private fun receive(app:AndJsApplication,item:DataItem,map:DataMap) {
        val client=Wearable.getDataClient(this)
        if(map.getString("target")!=Wearable.getNodeClient(this).localNode.blocking().id) return
        val asset=map.getAsset("snapshot") ?: return
        val response=client.getFdForAsset(asset).blocking()
        val text=try {
            response.inputStream.use {input->
                val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(8192)
                while(true) {val count=input.read(bytes);if(count<0) break;require(out.size()+count<=WatchSnapshot.MAX_BYTES);out.write(bytes,0,count)}
                out.toString("UTF-8")
            }
        } finally {response.release()}
        val origin=item.uri.host ?: return
        if(!app.watchStore.apply(text,origin,map.getLong("sequence"),map.getString("hash") ?: return)) return
        runCatching {androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(io.todu.andjs.widget.ScriptTileService::class.java)}
        val ack=PutDataMapRequest.create("/andjs/ack/$origin")
        ack.dataMap.apply {putString("token",requireNotNull(map.getString("token")));putString("hash",requireNotNull(map.getString("hash")));putLong("sequence",map.getLong("sequence"))}
        client.putDataItem(ack.asPutDataRequest().setUrgent()).blocking()
        publishWatchStatus(app)
    }
}

suspend fun recoverWatch(context:Context)=withContext(Dispatchers.IO) {
    val app=context.applicationContext as AndJsApplication
    publishWatchStatus(app)
}
private fun publishWatchStatus(app:AndJsApplication) {
    val local=Wearable.getNodeClient(app).localNode.blocking().id
    val request=PutDataMapRequest.create("/andjs/status/$local")
    request.dataMap.apply {putString("hash",app.watchStore.hash);putLong("sequence",app.watchStore.sequence);putBoolean("reset",app.watchStore.hash.isEmpty());putLong("seen",System.currentTimeMillis())}
    Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()).blocking()
}
