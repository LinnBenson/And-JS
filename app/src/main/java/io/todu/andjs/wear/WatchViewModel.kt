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
    private var background=false
    private var control:RunControl?=null
    fun run(document:ScriptDocument) {
        if(control!=null) return
        background=false
        val input=store.runData(document.item.id) ?: return
        val version=input.third
        val current=input.first
        val item=current.item
        val c=RunControl(persistVariables={namespace,values->store.writeVariables(item.id,version,namespace,values)},onInteraction={interaction.value=it})
        control=c;execution.value=HomeViewModel.Execution(item.name,icon=item.icon,color=item.color)
        val globals=input.second
        viewModelScope.launch {
            val result=try {runner.run(current.source,item.parameters,globals,c) {logs->execution.value=execution.value?.copy(logs=logs)}}
                catch(e:Exception) {RunResult("",emptyList(),e.message ?: "执行失败")}
            execution.value=if(background) null else HomeViewModel.Execution(item.name,false,result,item.icon,item.color,result.logs);control=null
        }
    }
    fun stop() {control?.stop()}
    fun background() {background=true;stop();interaction.value?.respond(null);interaction.value=null;execution.value=null}
    fun close() {if(execution.value?.running!=true) execution.value=null}
    override fun onCleared() {stop();super.onCleared()}
}
