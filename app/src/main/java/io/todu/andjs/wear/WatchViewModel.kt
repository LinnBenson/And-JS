package io.todu.andjs.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.todu.andjs.runtime.*
import io.todu.andjs.domain.ScriptDocument
import io.todu.andjs.ui.HomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class WatchViewModel(private val store:WatchStore,private val runner:ScriptRunner):ViewModel() {
    val snapshot=store.state
    val execution=MutableStateFlow<HomeViewModel.Execution?>(null)
    val interaction=MutableStateFlow<ScriptInteraction?>(null)
    @Volatile private var cacheEpoch=io.todu.andjs.data.AppCache.epoch.value
    init {viewModelScope.launch {
        io.todu.andjs.data.AppCache.epoch.collect {epoch->
            if(cacheEpoch<epoch) {background();cacheEpoch=epoch}
        }
    }}
    @Volatile private var background=false
    private var control:RunControl?=null
    var returnToWatchFace=false
        private set
    fun run(document:ScriptDocument,fromWatchFace:Boolean=false) {
        if(control!=null || io.todu.andjs.data.AppCache.busy.value) return
        val input=store.runData(document.item.id) ?: return
        cacheEpoch=io.todu.andjs.data.AppCache.epoch.value
        background=false
        returnToWatchFace=fromWatchFace
        val version=input.third
        val current=input.first
        val item=current.item
        val c=RunControl(loadScript={id->store.runData(id)?.also {check(it.third==version) {"脚本已更新，请重新运行"}}?.first},
            saveScriptVariables={id,values->store.writeVariables(id,version,"value",values)},persistVariables={namespace,values->store.writeVariables(item.id,version,namespace,values)},onInteraction={if(!background && cacheEpoch==io.todu.andjs.data.AppCache.epoch.value) interaction.value=it else it?.respond(null)})
        control=c;execution.value=HomeViewModel.Execution(item.name,icon=item.icon,color=item.color)
        val globals=input.second
        viewModelScope.launch {
            val result=try {runner.run(current.source,item.parameters,globals,c) {logs->execution.value=execution.value?.copy(logs=logs)}}
                catch(e:Exception) {RunResult("",emptyList(),e.message ?: "执行失败")}
            execution.value=if(background || c.closeRequested.get() || cacheEpoch!=io.todu.andjs.data.AppCache.epoch.value) null else HomeViewModel.Execution(item.name,false,result,item.icon,item.color,result.logs);control=null
        }
    }
    fun stop() {control?.stop()}
    fun background() {background=true;stop();interaction.value?.respond(null);interaction.value=null;execution.value=null}
    fun close() {control?.closeRequested?.set(true);background()}
    override fun onCleared() {stop();super.onCleared()}
}
