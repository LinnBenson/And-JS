package io.todu.andjs.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.todu.andjs.domain.*
import io.todu.andjs.runtime.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class HomeState(val items: List<Script> = emptyList(), val loading: Boolean = true, val error: String? = null)
class HomeViewModel(private val repository: ScriptRepository, private val runner: ScriptRunner, private val globalRepository: io.todu.andjs.data.GlobalRepository, private val categoryRepository: io.todu.andjs.data.CategoryRepository, private val watchSync:io.todu.andjs.wear.WatchSync,private val onRunStarted:()->Unit={}) : ViewModel() {
    private val mutable = MutableStateFlow(HomeState())
    val state = mutable.asStateFlow()
    var editor = MutableStateFlow<ScriptDocument?>(null); private set
    private val notice = MutableStateFlow<String?>(null)
    val message = notice.asStateFlow()
    val globals = MutableStateFlow<List<ScriptParameter>?>(null)
    fun openGlobals() = viewModelScope.launch {
        try {globals.value=globalRepository.load()} catch(e:Exception) {notice.value="无法读取全局变量"}
    }
    fun closeGlobals() {globals.value=null}
    fun saveGlobals(values:List<ScriptParameter>, onComplete:(Boolean)->Unit) = viewModelScope.launch {
        try {globalRepository.save(values);globals.value=values;refreshWatch();notice.value="已保存";onComplete(true)} catch(e:Exception) {notice.value="保存失败，请重试";onComplete(false)}
    }
    val watchWidgets=MutableStateFlow(watchSync.widgetIds())
    fun toggleWatchWidget(id:String,enabled:Boolean) {try {watchSync.setWidget(id,enabled);watchWidgets.value=watchSync.widgetIds();refreshWatch()} catch(_:Exception) {notice.value="保存失败"}}
    fun toggleWidget(item:Script,enabled:Boolean)=viewModelScope.launch {
        try {repository.save(ScriptDocument(item.copy(widget=enabled),repository.read(item.id)));refresh()} catch(_:Exception) {notice.value="保存失败"}
    }
    val watchIds=MutableStateFlow(watchSync.selection())
    val watchNotes=MutableStateFlow(watchSync.notes())
    fun saveWatchNote(index:Int,text:String,onComplete:(Boolean)->Unit)=viewModelScope.launch {
        try {watchSync.saveNote(index,text);watchNotes.value=watchSync.notes();refreshWatch();onComplete(true)}
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(_:Exception) {notice.value="便签保存失败，请重试";onComplete(false)}
    }
    val watchDirty=MutableStateFlow(false)
    val watchBusy=MutableStateFlow(false)
    private suspend fun watchSnapshot():io.todu.andjs.wear.WatchSnapshot {
        val items=repository.load().associateBy {it.id}
        return io.todu.andjs.wear.WatchSnapshot(watchIds.value.mapNotNull {id->items[id]?.let {ScriptDocument(it.copy(widget=id in watchSync.widgetIds()),repository.read(id))}},globalRepository.load(),watchSync.notes())
    }
    suspend fun previewSnapshot()=watchSnapshot()
    fun refreshWatchConnection()=viewModelScope.launch {try {watchSync.recover()} catch(_:Exception) {};refreshWatch()}
    fun refreshWatch()=viewModelScope.launch {
        try {watchDirty.value=io.todu.andjs.wear.WatchSnapshot.hash(watchSnapshot().encode())!=watchSync.acknowledged()}
        catch(_:Exception) {notice.value="无法读取待同步内容"}
    }
    fun selectWatch(ids:List<String>) {
        try {watchSync.select(ids);watchIds.value=ids;refreshWatch()} catch(_:Exception) {notice.value="无法保存手表列表"}
    }
    fun moveWatch(from:String,to:String) {
        val ids=watchIds.value.toMutableList();val a=ids.indexOf(from);val b=ids.indexOf(to)
        if(a>=0 && b>=0 && a!=b) {ids.add(b,ids.removeAt(a));watchIds.value=ids}
    }
    fun syncWatch() {
        if(watchBusy.value) return
        watchBusy.value=true
        viewModelScope.launch {
            try {
                val token=watchSync.send(watchSnapshot().encode())
                kotlinx.coroutines.withTimeout(30_000) {while(watchSync.isPending(token)) kotlinx.coroutines.delay(200)}
                notice.value="已同步"
            } catch(_:kotlinx.coroutines.TimeoutCancellationException) {notice.value="等待手表确认，稍后可重试"}
            catch(e:kotlinx.coroutines.CancellationException) {throw e}
            catch(e:Exception) {notice.value=e.message ?: "同步失败，请重试"}
            finally {watchBusy.value=false;refreshWatch()}
        }
    }
    init {
        viewModelScope.launch {watchSync.revision.collect {watchNotes.value=watchSync.notes();refreshWatch()}}
        viewModelScope.launch {try {watchSync.recover()} catch(_:Exception) {}}
    }
    val categories=MutableStateFlow<List<ScriptCategory>>(emptyList())
    val managingCategories=MutableStateFlow(false)
    fun saveCategories(values:List<ScriptCategory>) = viewModelScope.launch {
        val removed=categories.value.map {it.id}.toSet()-values.map {it.id}.toSet()
        if(state.value.items.any {it.category in removed}) {notice.value="请先将该分类中的脚本移到其他分类";return@launch}
        try {categoryRepository.save(values);categories.value=values;managingCategories.value=false} catch(e:Exception) {notice.value="分类名称不能为空或重复"}
    }
    fun move(fromId:String,toId:String) {
        val items=mutable.value.items.toMutableList()
        val from=items.indexOfFirst {it.id==fromId};val to=items.indexOfFirst {it.id==toId}
        if(from>=0 && to>=0 && from!=to) {val item=items.removeAt(from);items.add(to,item);mutable.value=mutable.value.copy(items=items)}
    }
    fun saveOrder() { val ids=state.value.items.map {it.id};viewModelScope.launch {try {repository.saveOrder(ids)} catch(e:Exception){notice.value="排序保存失败，请重试";refresh()}} }
    init { refresh();viewModelScope.launch {try {categories.value=categoryRepository.load()} catch(e:Exception){notice.value="无法读取分类"}} }

    private fun refresh() = viewModelScope.launch {
        try { mutable.value = HomeState(repository.load(), false);refreshWatch() }
        catch (e: Exception) { mutable.value = HomeState(loading = false, error = "无法读取脚本，请重试") }
    }
    fun retry() { refresh() }
    fun dismissMessage() { notice.value = null }
    suspend fun importScript(name:String,source:String,category:String):Boolean {
        return try {
            val item=Script(UUID.randomUUID().toString(),name,category.takeIf {id->categories.value.any {it.id==id}} ?: "","code")
            repository.save(ScriptDocument(item,source))
            mutable.value=HomeState(repository.load(),false)
            notice.value="已导入"
            true
        } catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(e:Exception) {notice.value="导入失败，请重试";false}
    }
    fun newScript() { editor.value = ScriptDocument(Script(UUID.randomUUID().toString(), "", categories.value.firstOrNull()?.id ?: "", "code"), "// 在这里编写 JavaScript\n") }
    fun edit(item: Script) = viewModelScope.launch {
        try { editor.value = ScriptDocument(item, repository.read(item.id)) }
        catch (e: Exception) { notice.value = "无法打开脚本" }
    }
    fun closeEditor() { editor.value = null }
    fun save(document: ScriptDocument) = viewModelScope.launch {
        try { repository.save(document); editor.value = null; refresh() }
        catch (e: Exception) { notice.value = "保存失败，请重试" }
    }
    fun delete(item: Script) = viewModelScope.launch {
        try { repository.delete(item.id); refresh() } catch (e: Exception) { notice.value = "删除失败，请重试" }
    }
    data class Execution(val name:String,val running:Boolean=true,val result:RunResult?=null,val icon:String="code",val color:String="mint",val logs:List<LogEntry> = emptyList())
    val execution=MutableStateFlow<Execution?>(null)
    val interaction=MutableStateFlow<ScriptInteraction?>(null)
    private var background=false
    private var runControl:RunControl?=null
    fun stopRun() {runControl?.stop()}
    fun background() {background=true;stopRun();interaction.value?.respond(null);interaction.value=null;execution.value=null}
    fun closeRun() {if(execution.value?.running!=true) execution.value=null}
    override fun onCleared() {stopRun();super.onCleared()}
    fun run(item: Script, draftSource:String?=null,onDraftParameters:((List<ScriptParameter>)->Unit)?=null) {
        if(runControl!=null) return
        background=false
        var latestItem=item
        val control=RunControl(loadScript={id->kotlinx.coroutines.runBlocking {
            repository.load().find {it.id==id}?.let {ScriptDocument(it,repository.read(id))}
        }},saveScriptVariables={id,values->kotlinx.coroutines.runBlocking {
            val saved=repository.load().find {it.id==id} ?: error("脚本已删除")
            repository.save(ScriptDocument(saved.copy(parameters=values),repository.read(id)))
            mutable.update {state->state.copy(items=state.items.map {if(it.id==id) it.copy(parameters=values) else it})}
            refreshWatch()
        }},onInteraction={interaction.value=it},persistVariables={namespace,values ->
            kotlinx.coroutines.runBlocking {
                if(namespace=="value") {
                    val updated=latestItem.copy(parameters=values)
                    if(draftSource==null) repository.save(ScriptDocument(updated,repository.read(item.id)))
                    else {
                        repository.load().find {it.id==item.id}?.let {saved->repository.save(ScriptDocument(saved.copy(parameters=values),repository.read(item.id)))}
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {onDraftParameters?.invoke(values)}
                    }
                    latestItem=updated
                    mutable.update {state->state.copy(items=state.items.map {if(it.id==item.id) it.copy(parameters=values) else it})}
                } else {
                    globalRepository.save(values)
                    if(globals.value!=null) globals.value=values
                }
                refreshWatch()
            }
        });runControl=control
        runCatching {onRunStarted()}
        execution.value=Execution(item.name,icon=item.icon,color=item.color)
        viewModelScope.launch {
            val result=try {runner.run(draftSource ?: repository.read(item.id),item.parameters,globalRepository.load(),control) {logs->execution.update {it?.copy(logs=logs)}}}
                catch(e:Exception) {RunResult("",emptyList(),"无法运行脚本："+(e.message ?: "读取失败"))}
            execution.value=if(background || control.closeRequested.get()) null else Execution(item.name,false,result,item.icon,item.color,result.logs)
            runControl=null
        }
    }
}
