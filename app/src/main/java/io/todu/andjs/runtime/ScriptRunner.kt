package io.todu.andjs.runtime

import io.todu.andjs.domain.ScriptParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.*
import java.util.concurrent.atomic.AtomicBoolean
import java.security.SecureRandom
import java.util.UUID
import okio.ByteString.Companion.toByteString

class RunControl(val persistVariables:((String,List<ScriptParameter>)->Unit)?=null, val loadScript:((String)->io.todu.andjs.domain.ScriptDocument?)?=null, val saveScriptVariables:((String,List<ScriptParameter>)->Unit)?=null, val onInteraction:((ScriptInteraction?)->Unit)?=null) {
    internal val cacheEpoch=io.todu.andjs.data.AppCache.epoch.value
    val closeRequested = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)
    private val activeStops=java.util.concurrent.ConcurrentHashMap<Any,()->Unit>()
    fun stop() {
        stopped.set(true)
        activeStops.values.forEach {runCatching {it()}}
    }
    fun registerStop(action:()->Unit):()->Unit {
        val key=Any()
        val invoked=AtomicBoolean(false)
        val once:()->Unit={if(invoked.compareAndSet(false,true)) action()}
        activeStops[key]=once
        if(stopped.get()) runCatching {once()}
        return {activeStops.remove(key);Unit}
    }
}
data class LogEntry(val level:String,val text:String,val imageRatio:Float=1f,val imageWidth:Int=240,val imageWidthPercent:Float?=null,val installUri:String?=null)
data class RunResult(val output:String, val logs:List<LogEntry>, val error:String?=null, val stopped:Boolean=false, val elapsedMs:Long=0)
interface ScriptRunner {
    suspend fun run(source:String, parameters:List<ScriptParameter>, globals:List<ScriptParameter>, control:RunControl, onLogs:(List<LogEntry>)->Unit = {}):RunResult
}
private class ScriptExited: Error()
private class ScriptCallbackExited: Error()
private class RunAborted(val cancelled:Boolean): Error()

/** Each invocation has an independent safe scope; Java reflection and host objects are not exposed. */
class RhinoScriptRunner(private val networkRoot:java.io.File=java.io.File(System.getProperty("java.io.tmpdir"),"andjs-network"),private val platform:PlatformApi?=null,private val lowMemory:Boolean=false) : ScriptRunner {
    // Bytecode is reusable; execution scopes and API bindings remain fresh for each run.
    private data class CompiledProgram(val script:org.mozilla.javascript.Script,val size:Int)
    private val compiled=LinkedHashMap<String,CompiledProgram>(8,0.75f,true)
    private val sourceCache=ScriptSourceCache(java.io.File(networkRoot,"andjs-source-cache"))
    private var compiledEpoch=-1L
    private fun program(cx:Context,code:String,prepare:(String)->String):org.mozilla.javascript.Script {
        val key=sourceCache.key(code)
        synchronized(compiled) {
            val epoch=io.todu.andjs.data.AppCache.epoch.value
            if(compiledEpoch!=epoch) {compiled.clear();compiledEpoch=epoch}
            compiled[key]?.let {return it.script}
        }
        val converted=sourceCache.read(key) ?: prepare(code).also {sourceCache.write(key,it)}
        val script=cx.compileString(converted,"main.js",1,null)
        val limit=if(lowMemory) 128_000 else 512_000
        if(converted.length<=limit) synchronized(compiled) {
            // Includes generated helpers; bound both total source size and entry count.
            compiled[key]=CompiledProgram(script,converted.length)
            while(compiled.size>(if(lowMemory) 4 else 8) || compiled.values.sumOf {it.size}>limit) {
                compiled.remove(compiled.keys.first())
            }
        }
        return script
    }
    override suspend fun run(source:String, parameters:List<ScriptParameter>, globals:List<ScriptParameter>, control:RunControl, onLogs:(List<LogEntry>)->Unit):RunResult = withContext(Dispatchers.Default) {
        val started=System.nanoTime()
        val maxLogs=200
        val maxOutput=if(lowMemory) 8000 else 32000
        val heap=Runtime.getRuntime()
        var baseline=heap.totalMemory()-heap.freeMemory()
        var preparing=false
        var preparationDeadline=0L
        val logs=mutableListOf<LogEntry>()
        val logIds=mutableMapOf<String,Int>()
        var output=""
        var imageLogChars=0
        var waitingNanos=0L
        var interactionWaitingNanos=0L
        var callbackDepth=0
        var httpCloseDeadline=0L
        val httpSessions=mutableListOf<HttpSession>()
        val hotspotPumps=mutableListOf<()->Unit>()
        val socketManager=lazy {ScriptWebSockets(control,lowMemory)}
        val deviceSession=lazy {platform?.openDeviceSession(control) ?: error("当前环境不支持设备监听")}
        val bleSession=lazy {platform?.openBle(control) ?: error("当前环境不支持 BLE")}
        var globalValues=globals.toList()
        fun checkRun() {
            if(httpCloseDeadline!=0L) {
                if(System.nanoTime()>=httpCloseDeadline) throw Context.reportRuntimeError("onClose 执行时间超过 250 毫秒")
            } else if(control.stopped.get()) throw RunAborted(true)
            if(preparing && System.nanoTime()>preparationDeadline) throw Context.reportRuntimeError("脚本兼容转换超时")
            if(!preparing && lowMemory && heap.totalMemory()-heap.freeMemory()-baseline>16L*1024*1024) throw Context.reportRuntimeError("脚本内存占用过高，已停止")
        }
        val factory=object:ContextFactory() {
            override fun makeContext():Context = super.makeContext().apply {
                languageVersion=Context.VERSION_ES6
                isInterpretedMode=true
                instructionObserverThreshold=5_000
                maximumInterpreterStackDepth=if(lowMemory) 128 else 256
                setClassShutter {false}
            }
            override fun observeInstructionCount(cx:Context, count:Int) {checkRun()}
        }
        val cacheTicket=io.todu.andjs.data.AppCache.enter(control) ?: return@withContext RunResult("",emptyList(),stopped=true)
        try {
            require(source.length<=if(lowMemory) 256_000 else 1_000_000) {"脚本超过当前设备大小限制"}
            factory.call(ContextAction<Unit> {cx ->
                val session by lazy {HttpSession(networkRoot,control,lowMemory).also {httpSessions.add(it)}}
                val defaultHttpClient by lazy {HttpClient(session,control,lowMemory)}
                fun execute(code:String, initialParameters:List<ScriptParameter>, persist:((String,List<ScriptParameter>)->Unit)?, depth:Int):Any? {
                require(depth<=8) {"脚本调用最多允许 8 层"}
                require(code.length<=if(lowMemory) 256_000 else 1_000_000) {"脚本超过当前设备大小限制"}
                val scopeSockets=mutableListOf<ScriptWebSockets.Connection>()
                val scopeBle=mutableListOf<BleHandle>()
                val scopeHotspots=mutableListOf<HotspotSession>()
                val scopeHotspotClosers=mutableListOf<()->Unit>()
                try {
                var returned:Any?=Undefined.instance
                val scope=cx.initSafeStandardObjects()
                fun objectValue(parent:Scriptable, key:String):Scriptable {
                    val obj=cx.newObject(scope); ScriptableObject.putProperty(parent,key,obj);return obj
                }
                fun function(parent:Scriptable,key:String,allowOnClose:Boolean=false,body:(Array<out Any?>)->Any?) {
                    val fn=object:BaseFunction() {
                        override fun call(context:Context, scope:Scriptable, thisObj:Scriptable, args:Array<out Any?>):Any? {
                            checkRun()
                            return try {
                                require(httpCloseDeadline==0L || allowOnClose) {"onClose 仅支持 console.log 和文字日志方法"}
                                body(args)
                            } catch(e:Exception) {if(e is RhinoException) throw e;throw Context.reportRuntimeError(e.message ?: "调用失败")}
                        }
                    }
                    fn.parentScope=scope;fn.prototype=ScriptableObject.getFunctionPrototype(scope)
                    ScriptableObject.putProperty(parent,key,fn)
                }
                fun js(value:Any?):Any? = when(value) {
                    null -> null
                    is List<*> -> cx.newArray(scope,value.map {js(it)}.toTypedArray())
                    is Map<*,*> -> cx.newObject(scope).also {o->value.forEach {(k,v)->(o as ScriptableObject).defineProperty(k.toString(),js(v),ScriptableObject.EMPTY)}}
                    else -> value
                }
                // JSON.stringify handles arrays and objects; fall back to string conversion for circular values.
                fun display(value:Any?):String {
                    checkRun()
                    if(value is Scriptable) {
                        try {
                            val result=NativeJSON.stringify(cx,scope,value,null,3)
                            if(result !is Undefined) return Context.toString(result).take(maxOutput)
                        } catch(e:RhinoException) { /* Circular values remain printable. */ }
                    }
                    return Context.toString(value).take(maxOutput)
                }
                val api=objectValue(scope,"andjs")
                fun values(namespace:String, entries:List<ScriptParameter>) {
                    var localValues=entries.toList()
                    fun current()=if(namespace=="global") globalValues else localValues
                    fun update(next:List<ScriptParameter>) {if(namespace=="global") globalValues=next else localValues=next}
                    val target=objectValue(api,namespace)
                    fun key(args:Array<out Any?>):String {
                        require(args.firstOrNull() is CharSequence) {"键必须为字符串"}
                        return args[0].toString().also {require(it.isNotBlank() && it.length<=200) {"键须为 1–200 字符"}}
                    }
                    function(target,"all") {
                        js(current().associate {it.key to ParameterValues.parse(it)})
                    }
                    function(target,"get") {args->
                        val name=key(args)
                        val entry=current().find {it.key==name}
                        if(entry!=null) js(ParameterValues.parse(entry)) else args.getOrElse(1) {Undefined.instance}
                    }
                    function(target,"set") {args->
                        val name=key(args)
                        val value=args.getOrElse(1) {Undefined.instance}
                        val parameter=when(value) {
                            is Boolean -> ScriptParameter(name,"boolean",value.toString())
                            is CharSequence -> ScriptParameter(name,"string",value.toString())
                            is Number -> {require(value.toDouble().isFinite()) {"数字必须有限"};ScriptParameter(name,"number",value.toString())}
                            is NativeArray, is NativeObject -> {
                                val seen=java.util.IdentityHashMap<Scriptable,Boolean>()
                                fun validate(v:Any?,depth:Int) {
                                    require(depth<=32) {"数组嵌套过深"}
                                    when(v) {
                                        null,is Boolean,is CharSequence -> Unit
                                        is Number -> require(v.toDouble().isFinite()) {"数组数字必须有限"}
                                        is NativeArray -> {
                                            require(v.length<=10000 && seen.put(v,true)==null) {"数组过大或包含循环引用"}
                                            for(i in 0 until v.length.toInt()) validate(v.get(i,v),depth+1)
                                            seen.remove(v)
                                        }
                                        is NativeObject -> {
                                            require(seen.put(v,true)==null) {"数组包含循环引用"}
                                            v.ids.forEach {id->validate(if(id is Number) v.get(id.toInt(),v) else v.get(id.toString(),v),depth+1)}
                                            seen.remove(v)
                                        }
                                        else -> throw IllegalArgumentException("数组只能包含 JSON 值")
                                    }
                                }
                                validate(value,0)
                                val encoded=Context.toString(NativeJSON.stringify(cx,scope,value,null,null))
                                ScriptParameter(name,"array",encoded)
                            }
                            else -> throw IllegalArgumentException("仅支持 boolean、string、number、array")
                        }
                        require(parameter.value.length<=1_000_000) {"变量内容过大"}
                        val next=current().toMutableList()
                        val index=next.indexOfFirst {it.key==name}
                        if(index<0) next.add(parameter) else next[index]=parameter
                        val save=persist ?: throw Context.reportRuntimeError("当前环境不支持保存变量")
                        save(namespace,next)
                        update(next)
                        Undefined.instance
                    }
                    function(target,"delete") {args->
                        val name=key(args)
                        if(current().none {it.key==name}) false else {
                            val next=current().filter {it.key!=name}
                            val save=persist ?: throw Context.reportRuntimeError("当前环境不支持保存变量")
                            save(namespace,next)
                            update(next)
                            true
                        }
                    }
                }
                values("value",initialParameters);values("global",globalValues)
                val log=objectValue(api,"log")
                fun logId(value:Any?):String? {
                    if(value==null || value is Undefined) return null
                    require(value is CharSequence || value is Number) {"日志 id 须为字符串或数字"}
                    if(value is Number) require(value.toDouble().isFinite()) {"日志 id 须为有限数字"}
                    return Context.toString(value).also {require(it.isNotEmpty() && it.length<=200) {"日志 id 长度须为 1–200 字符"}}
                }
                fun logSlot(id:String?):Int?=id?.let {logIds[it]}
                fun canLog(index:Int?):Boolean {
                    if(index!=null || logs.size<maxLogs) return true
                    if(logs.size==maxLogs) {logs.add(LogEntry("warn","日志已达到 $maxLogs 条上限"));onLogs(logs.toList())}
                    return false
                }
                fun writeLog(entry:LogEntry,id:String?) {
                    val index=logSlot(id)
                    if(!canLog(index)) return
                    val old=index?.let {logs[it]}
                    imageLogChars+=(if(entry.level=="img") entry.text.length else 0)-(if(old?.level=="img") old.text.length else 0)
                    if(index!=null) logs[index]=entry else {
                        if(id!=null) logIds[id]=logs.size
                        logs.add(entry)
                    }
                    onLogs(logs.toList())
                }
                listOf("info","warn","error").forEach {level->function(log,level,allowOnClose=true) {args->
                    require(args.size<=2) {"请使用 (value, id)，多个值请先组合为字符串或数组"}
                    val id=logId(args.getOrNull(1))
                    writeLog(LogEntry(level,display(if(args.isEmpty()) Undefined.instance else args[0]).take(2_000)),id)
                    Undefined.instance
                }}
                function(objectValue(scope,"console"),"log",allowOnClose=true) {args->
                    writeLog(LogEntry("info",args.joinToString(" ") {display(it)}.take(2_000)),null)
                    Undefined.instance
                }
                function(scope,"exit") {args->
                    returned=args.getOrElse(0) {Undefined.instance}
                    if(depth==0 || callbackDepth>0) output=when {
                        args.isEmpty() || args[0] is Undefined -> ""
                        args[0] is Boolean -> if(args[0] == true) "执行成功" else "执行失败"
                        else -> display(args[0])
                    }
                    if(callbackDepth>0) throw ScriptCallbackExited()
                    throw ScriptExited()
                }
                function(api,"sleep") {args->
                    val ms=Context.toNumber(args.firstOrNull())
                    require(ms.isFinite() && ms>=0 && ms<=15_000) {"等待时间须在 0–15000 毫秒之间"}
                    val until=System.nanoTime()+(ms*1_000_000).toLong()
                    while(System.nanoTime()<until) {checkRun();Thread.sleep(10)}
                    Undefined.instance
                }
                val ui=objectValue(api,"ui")
                function(ui,"close") { control.closeRequested.set(true);control.stop();throw RunAborted(true) }
                listOf("input","select","multiSelect","confirm").forEach {kind -> function(ui,kind) {args ->
                    require(args.firstOrNull() is CharSequence) {"标题必须为字符串"}
                    val title=args[0].toString().take(200)
                    val options=if(kind=="select" || kind=="multiSelect") {
                        val array=args.getOrNull(2)
                        require(array is NativeArray && array.length in 1..200) {"选项须为 1–200 项字符串数组"}
                        (0 until array.length.toInt()).map {index ->
                            val value=array.get(index,array)
                            require(value is CharSequence && value.length<=500) {"选项须为不超过 500 字符的字符串"}
                            value.toString()
                        }
                    } else emptyList()
                    val initial=if(kind=="input") {
                        val value=args.getOrElse(2) {""}
                        require(value is CharSequence && value.length<=10000) {"默认值须为不超过 10000 字符的字符串"}
                        value.toString()
                    } else ""
                    val handler=control.onInteraction ?: throw Context.reportRuntimeError("当前环境不支持界面交互")
                    val contentValue=args.getOrElse(1) {""}
                    require(contentValue is CharSequence && contentValue.length<=2000) {"说明须为不超过 2000 字符的字符串"}
                    val request=ScriptInteraction(kind,title,options,initial,contentValue.toString())
                    val waitStart=System.nanoTime()
                    val releaseWait=control.registerStop {request.respond(null)}
                    val answer=try {
                        handler(request)
                        request.await {if(control.stopped.get()) throw RunAborted(true)}
                    } finally {
                        releaseWait()
                        val elapsed=System.nanoTime()-waitStart
                        waitingNanos+=elapsed;interactionWaitingNanos+=elapsed
                        handler(null)
                    }
                    js(answer)
                }}
                fun httpOptions(value:Any?):org.json.JSONObject {
                    if(value==null || value is Undefined) return org.json.JSONObject()
                    require(value is NativeObject) {"options 必须为对象"}
                    return org.json.JSONObject(Context.toString(NativeJSON.stringify(cx,scope,value,null,null)))
                }
                fun jsonValue(value:Any):Any?=NativeJSON.parse(cx,scope,value.toString(),Callable {_,_,_,v->v[1]})
                fun throwHttp(failure:HttpFailure):Nothing {
                    val details=jsonValue(failure.details) as Scriptable
                    val error=cx.newObject(scope,"Error",arrayOf(failure.details.optString("message")))
                    details.ids.forEach {id->val key=id.toString();ScriptableObject.putProperty(error,key,ScriptableObject.getProperty(details,key))}
                    throw JavaScriptException(error,"",0)
                }
                fun decodeResponse(response:org.json.JSONObject,responseType:String):Scriptable {
                    val result=jsonValue(response) as Scriptable
                    if(responseType=="json" || responseType=="auto") {
                        val text=response.optString("text").removePrefix("\uFEFF").trim()
                        val data=try {
                            if(responseType=="json" && text.isEmpty()) null else NativeJSON.parse(cx,scope,text,Callable {_,_,_,v->v[1]}).also {
                                ScriptableObject.putProperty(result,"isJson",response.optBoolean("ok"))
                            }
                        } catch(e:RhinoException) {
                            if(responseType=="json") throw HttpFailure(org.json.JSONObject().put("name","HttpError").put("code","INVALID_RESPONSE")
                                .put("message","响应不是有效 JSON").put("phase","decode").put("status",response.optInt("status"))
                                .put("url",response.optString("url")).put("method",response.optString("method","GET")).put("attempts",response.optInt("attempts",1))
                                .put("elapsedMs",response.optLong("elapsedMs")).put("retryable",false).put("response",response),e)
                            response.optString("text")
                        }
                        ScriptableObject.putProperty(result,"data",data)
                    }
                    return result
                }
                fun installHttp(target:Scriptable,client:HttpClient,allowCreate:Boolean) {
                    fun network(args:Array<out Any?>,method:String?=null,download:Boolean=false):Any? {
                        val address=args.firstOrNull()?.toString().orEmpty()
                        try {
                            require(args.firstOrNull() is CharSequence) {"URL 必须为字符串"}
                            val options=httpOptions(args.getOrNull(1))
                            if(method!=null) options.put("method",method)
                            if(download) options.put("responseType","file")
                            val start=System.nanoTime()
                            val response=try {client.request(address,options)} finally {waitingNanos+=System.nanoTime()-start}
                            checkRun()
                            return decodeResponse(response,client.responseType(options))
                        } catch(e:Exception) {
                            if(control.stopped.get()) throw RunAborted(true)
                            throwHttp(httpFailure(e,address,method ?: "GET"))
                        }
                    }
                    function(target,"request") {network(it)}
                    listOf("get","post","put","patch","delete","head","options").forEach {method->function(target,method) {network(it,method.uppercase())}}
                    function(target,"download") {network(it,"GET",true)}
                    function(target,"clearCookies") {client.clearCookies();Undefined.instance}
                    function(target,"setCookies") {args->
                        try {
                            require(args.size>=2 && args[0] is CharSequence && (args[1] is CharSequence || args[1] is NativeObject)) {"setCookies 需要 URL 和 Cookie 字符串或对象"}
                            val value=if(args[1] is CharSequence) args[1].toString() else httpOptions(args[1])
                            client.setCookies(args[0].toString(),value);Undefined.instance
                        } catch(e:Exception) {throwHttp(httpFailure(e,"","","cookies"))}
                    }
                    function(target,"getCookies") {args->
                        try {require(args.firstOrNull() is CharSequence) {"getCookies 需要 URL"};jsonValue(client.getCookies(args[0].toString()))}
                        catch(e:Exception) {throwHttp(httpFailure(e,"","","cookies"))}
                    }
                    function(target,"batch") {args->
                        try {
                            require(args.firstOrNull() is NativeArray) {"batch 需要请求数组"}
                            val requests=org.json.JSONArray(Context.toString(NativeJSON.stringify(cx,scope,args[0],null,null)))
                            val options=httpOptions(args.getOrNull(1))
                            val start=System.nanoTime()
                            val packets=try {client.batch(requests,options)} finally {waitingNanos+=System.nanoTime()-start}
                            checkRun()
                            val results=(0 until packets.length()).map {index->
                                val packet=packets.getJSONObject(index)
                                val responseType=packet.optString("_responseType","text")
                                packet.remove("_responseType")
                                val result=jsonValue(packet) as Scriptable
                                packet.optJSONObject("response")?.let {response->
                                    try {ScriptableObject.putProperty(result,"response",decodeResponse(response,responseType))}
                                    catch(e:HttpFailure) {ScriptableObject.putProperty(result,"ok",false);ScriptableObject.putProperty(result,"error",jsonValue(e.details))}
                                }
                                result
                            }
                            cx.newArray(scope,results.toTypedArray<Any>())
                        } catch(e:Exception) {
                            if(control.stopped.get()) throw RunAborted(true)
                            throwHttp(httpFailure(e,"","","batch"))
                        }
                    }
                    if(allowCreate) function(target,"createClient") {args->
                        try {
                            val defaults=httpOptions(args.firstOrNull())
                            require(httpSessions.size<(if(lowMemory) 4 else 8)) {"独立客户端数量已达到设备上限"}
                            val newSession=HttpSession(networkRoot,control,lowMemory)
                            try {
                                val native=HttpClient(newSession,control,lowMemory,defaults)
                                httpSessions.add(newSession)
                                cx.newObject(scope).also {installHttp(it,native,false)}
                            } catch(e:Exception) {newSession.close();throw e}
                        } catch(e:Exception) {throwHttp(httpFailure(e,"","","client"))}
                    }
                }
                fun throwWebSocket(error:Exception,url:String=""):Nothing {
                    if(control.stopped.get()) throw RunAborted(true)
                    val failure=error as? WebSocketFailure ?: if(error is InterruptedException) wsFailure("CANCELLED","等待已取消",url)
                        else wsFailure("INVALID_OPTIONS",error.message ?: "WebSocket 参数无效",url)
                    val details=jsonValue(failure.details) as Scriptable
                    val value=cx.newObject(scope,"Error",arrayOf(failure.details.optString("message")))
                    details.ids.forEach {id->val name=id.toString();ScriptableObject.putProperty(value,name,ScriptableObject.getProperty(details,name))}
                    throw JavaScriptException(value,"",0)
                }
                val wsApi=objectValue(api,"ws")
                function(wsApi,"connect") {args->
                    val address=args.firstOrNull()?.toString().orEmpty()
                    try {
                        require(args.firstOrNull() is CharSequence) {"URL 必须为字符串"}
                        val options=httpOptions(args.getOrNull(1))
                        val start=System.nanoTime()
                        val connection=try {socketManager.value.connect(address,options)} finally {waitingNanos+=System.nanoTime()-start}
                        scopeSockets.add(connection)
                        checkRun()
                        cx.newObject(scope).also {socket->
                            var messageHandler:org.mozilla.javascript.Function?=null
                            var errorHandler:org.mozilla.javascript.Function?=null
                            var closeHandler:org.mozilla.javascript.Function?=null
                            var listening=false
                            var closeDelivered=false
                            fun decodeMessage(message:org.json.JSONObject):Scriptable {
                                val result=jsonValue(message) as Scriptable
                                if(message.getString("type")=="text") {
                                    var isJson=false
                                    val data=try {NativeJSON.parse(cx,scope,message.getString("text"),Callable {_,_,_,v->v[1]}).also {isJson=true}}
                                        catch(_:RhinoException) {message.getString("text")}
                                    ScriptableObject.putProperty(result,"data",data)
                                    ScriptableObject.putProperty(result,"isJson",isJson)
                                }
                                return result
                            }
                            fun invokeCallback(handler:org.mozilla.javascript.Function?,event:Any?) {
                                if(handler==null) return
                                checkRun();callbackDepth++
                                try {handler.call(cx,scope,socket,arrayOf(event));checkRun()}
                                finally {callbackDepth--}
                            }
                            listOf("onMessage","onError","onClose").forEach {name->function(socket,name) {values->
                                val handler=values.firstOrNull()
                                require(handler is org.mozilla.javascript.Function) {"$name 需要函数"}
                                when(name) {"onMessage"->messageHandler=handler;"onError"->errorHandler=handler;else->closeHandler=handler}
                                socket
                            }}
                            function(socket,"listen") {
                                require(!listening && callbackDepth==0) {"不能在回调中或重复进入 listen"}
                                require(messageHandler!=null) {"请先注册 onMessage"}
                                listening=true
                                try {
                                    while(true) {
                                        checkRun()
                                        var terminal:WebSocketFailure?=null
                                        val start=System.nanoTime()
                                        val message=try {connection.receive(100)} catch(e:WebSocketFailure) {
                                            if(e.details.optString("code")!="RECEIVE_TIMEOUT") terminal=e
                                            null
                                        } finally {waitingNanos+=System.nanoTime()-start}
                                        if(control.stopped.get()) throw RunAborted(true)
                                        if(terminal!=null) {
                                            if(!closeDelivered) {
                                                closeDelivered=true
                                                val error=terminal!!
                                                if(error.details.optString("code")!="CLOSED") {
                                                    if(errorHandler==null) throwWebSocket(error,address)
                                                    invokeCallback(errorHandler,jsonValue(error.details))
                                                }
                                                invokeCallback(closeHandler,jsonValue(connection.closeInfo()))
                                            }
                                            break
                                        }
                                        if(message!=null) invokeCallback(messageHandler,decodeMessage(message))
                                    }
                                    jsonValue(connection.closeInfo())
                                } finally {listening=false}
                            }
                            function(socket,"send") {values->
                                try {
                                    require(values.isNotEmpty() && values[0] !is Undefined && values[0] !is org.mozilla.javascript.Function) {"send 需要文字、JSON 值或 Base64 数据"}
                                    val options=httpOptions(values.getOrNull(1))
                                    val type=options.optString("type",if(values[0] is CharSequence) "text" else "json")
                                    when(type) {
                                        "text"->{require(values[0] is CharSequence) {"text 模式需要字符串"};connection.send(values[0].toString())}
                                        "json"->{
                                            val encoded=Context.toString(NativeJSON.stringify(cx,scope,values[0],null,null))
                                            require(encoded!="undefined") {"消息无法编码为 JSON"}
                                            connection.send(encoded)
                                        }
                                        "base64"->{
                                            require(values[0] is CharSequence) {"base64 模式需要字符串"}
                                            val encoded=values[0].toString()
                                            if(encoded.length>((socketManager.value.messageLimit()+2)/3)*4) throw wsFailure("MESSAGE_TOO_LARGE","二进制消息超过大小限制",address)
                                            connection.send(java.util.Base64.getDecoder().decode(encoded).toByteString())
                                        }
                                        else->throw IllegalArgumentException("type 须为 text、json 或 base64")
                                    }
                                } catch(e:Exception) {throwWebSocket(e,address)}
                            }
                            function(socket,"receive") {values->
                                try {
                                    require(!listening) {"listen 期间不能调用 receive"}
                                    val wait=values.getOrNull(0)?.takeUnless {it is Undefined}?.let {value->
                                        require(value is Number && value.toDouble().isFinite() && value.toDouble()%1==0.0 && value.toDouble() in 1.0..120000.0) {"receive 超时须为有效的毫秒整数"}
                                        value.toLong()
                                    }
                                    val start=System.nanoTime()
                                    val message=try {connection.receive(wait)} finally {waitingNanos+=System.nanoTime()-start}
                                    checkRun()
                                    decodeMessage(message)
                                } catch(e:Exception) {throwWebSocket(e,address)}
                            }
                            function(socket,"close") {values->
                                try {
                                    val code=values.getOrNull(0)?.takeUnless {it is Undefined}?.let {
                                        require(it is Number && it.toDouble().isFinite() && it.toDouble()%1==0.0 && it.toDouble() in 1000.0..4999.0) {"关闭码无效"};it.toInt()
                                    } ?: 1000
                                    val reason=values.getOrNull(1)?.takeUnless {it is Undefined}?.let {require(it is CharSequence) {"关闭原因须为字符串"};it.toString()} ?: ""
                                    val start=System.nanoTime()
                                    try {connection.close(code,reason)} finally {waitingNanos+=System.nanoTime()-start}
                                } catch(e:Exception) {throwWebSocket(e,address)}
                            }
                        }
                    } catch(e:Exception) {if(e is JavaScriptException) throw e;throwWebSocket(e,address)}
                }
                fun bleFailure(e:Exception):Nothing {
                    if(control.stopped.get()) throw RunAborted(true)
                    if(e is RhinoException) throw e
                    val details=(e as? BleFailure)?.details ?: org.json.JSONObject().put("name","BleError")
                        .put("code",if(e is SecurityException) "PERMISSION_DENIED" else "INVALID_OPTIONS").put("message",e.message ?: "BLE 调用失败").put("status",0)
                    val value=cx.newObject(scope,"Error",arrayOf(details.optString("message")))
                    details.keys().forEach {name->ScriptableObject.putProperty(value,name,js(details.get(name)))}
                    throw JavaScriptException(value,"",0)
                }
                val ble=objectValue(api,"ble")
                listOf("connect","scan","advertise").forEach {kind->function(ble,kind) {args->
                    try {
                        val options=httpOptions(args.getOrNull(if(kind=="connect") 1 else 0))
                        val nativeArgs=org.json.JSONArray()
                        if(kind=="connect") {require(args.firstOrNull() is CharSequence) {"需要蓝牙地址"};nativeArgs.put(args[0].toString())}
                        nativeArgs.put(options)
                        val start=System.nanoTime()
                        val handle=try {bleSession.value.open(kind,nativeArgs)} finally {waitingNanos+=System.nanoTime()-start}
                        scopeBle.add(handle);checkRun()
                        cx.newObject(scope).also {obj->
                            var message:org.mozilla.javascript.Function?=null
                            var error:org.mozilla.javascript.Function?=null
                            var closed:org.mozilla.javascript.Function?=null
                            var listening=false
                            var delivered=false
                            fun callback(fn:org.mozilla.javascript.Function?,event:Any?) {
                                if(fn==null) return
                                checkRun();callbackDepth++
                                try {fn.call(cx,scope,obj,arrayOf(event));checkRun()} finally {callbackDepth--}
                            }
                            listOf("onMessage","onError","onClose").forEach {name->function(obj,name) {values->
                                val fn=values.firstOrNull();require(fn is org.mozilla.javascript.Function) {"$name 需要函数"}
                                when(name) {"onMessage"->message=fn;"onError"->error=fn;else->closed=fn};obj
                            }}
                            function(obj,"close") {handle.close();Undefined.instance}
                            function(obj,"info") {jsonValue(handle.info())}
                            function(obj,"receive") {values->
                                try {
                                    require(!listening) {"listen 期间不能调用 receive"}
                                    val timeout=values.firstOrNull()?.takeUnless {it is Undefined} ?: 10000
                                    require(timeout is Number && timeout.toDouble()%1==0.0 && timeout.toDouble() in 1.0..15000.0) {"timeout 须为 1–15000 的毫秒整数"}
                                    val start=System.nanoTime()
                                    val event=try {handle.poll(timeout.toLong())} finally {waitingNanos+=System.nanoTime()-start}
                                    checkRun();event?.let {jsonValue(it)}
                                } catch(e:Exception) {bleFailure(e)}
                            }
                            function(obj,"listen") {
                                require(!listening && callbackDepth==0) {"不能在回调中或重复进入 listen"}
                                require(message!=null || kind=="advertise") {"请先注册 onMessage"}
                                listening=true
                                try {
                                    while(true) {
                                        checkRun()
                                        var failure:BleFailure?=null
                                        val start=System.nanoTime()
                                        val event=try {handle.poll(100)} catch(e:BleFailure) {failure=e;null} finally {waitingNanos+=System.nanoTime()-start}
                                        checkRun()
                                        if(failure!=null) {
                                            if(!delivered) {
                                                delivered=true
                                                val e=failure!!
                                                if(e.details.optString("code")!="CLOSED") {if(error==null) bleFailure(e);callback(error,jsonValue(e.details))}
                                                callback(closed,jsonValue(handle.info()))
                                            }
                                            break
                                        }
                                        if(event!=null) callback(message,jsonValue(event))
                                    }
                                    jsonValue(handle.info())
                                } finally {listening=false}
                            }
                            if(kind=="connect") listOf("services","read","send","write","subscribe","unsubscribe","mtu").forEach {name->function(obj,name) {values->
                                try {
                                    val arguments=org.json.JSONArray(Context.toString(NativeJSON.stringify(cx,scope,cx.newArray(scope,values),null,null)))
                                    val start=System.nanoTime()
                                    val result=try {handle.call(name,arguments)} finally {waitingNanos+=System.nanoTime()-start}
                                    checkRun()
                                    if(result is org.json.JSONObject || result is org.json.JSONArray) jsonValue(result) else js(result)
                                } catch(e:Exception) {bleFailure(e)}
                            }}
                        }
                    } catch(e:Exception) {bleFailure(e)}
                }}
                fun deviceFailure(e:Exception):Nothing {
                    if(control.stopped.get()) throw RunAborted(true)
                    if(e is RhinoException) throw e
                    val details=(e as? DeviceFailure)?.details ?: org.json.JSONObject().put("name","DeviceError")
                        .put("code",when(e) {is SecurityException->"PERMISSION_DENIED";is java.io.IOException->"IO_ERROR";is android.hardware.camera2.CameraAccessException->"DEVICE_UNAVAILABLE";is UnsupportedOperationException->"UNSUPPORTED";is IllegalArgumentException,is org.json.JSONException->"INVALID_OPTIONS";else->"DEVICE_ERROR"}).put("message",e.message ?: "设备调用失败").put("status",0)
                    val value=cx.newObject(scope,"Error",arrayOf(details.optString("message")))
                    details.keys().forEach {name->ScriptableObject.putProperty(value,name,js(details.get(name)))}
                    throw JavaScriptException(value,"",0)
                }
                listOf("sensors","network","torch","nfc","dnd").forEach {kind->
                val target=objectValue(api,kind)
                function(target,"status") {try {jsonValue(deviceSession.value.status(kind))} catch(e:Exception) {deviceFailure(e)}}
                if(kind=="network") function(target,"scanPorts") {args->
                    try {jsonValue(TcpPortScanner.scan(httpOptions(args.getOrNull(0)),control,lowMemory))}
                    catch(e:Exception) {deviceFailure(e)}
                }
                function(target,if(kind in listOf("torch","dnd")) "open" else "watch") {args->
                    try {
                        val options=httpOptions(args.getOrNull(0))
                        val handle=deviceSession.value.open(kind,options)
                        scopeBle.add(handle);checkRun()
                        cx.newObject(scope).also {obj->
                            var message:org.mozilla.javascript.Function?=null
                            var error:org.mozilla.javascript.Function?=null
                            var closed:org.mozilla.javascript.Function?=null
                            var listening=false
                            var delivered=false
                            fun callback(fn:org.mozilla.javascript.Function?,event:Any?) {
                                if(fn==null) return
                                checkRun();callbackDepth++
                                try {fn.call(cx,scope,obj,arrayOf(event));checkRun()} finally {callbackDepth--}
                            }
                            listOf("onMessage","onError","onClose").forEach {name->function(obj,name) {values->
                                val fn=values.firstOrNull();require(fn is org.mozilla.javascript.Function) {"$name 需要函数"}
                                when(name) {"onMessage"->message=fn;"onError"->error=fn;else->closed=fn};obj
                            }}
                            function(obj,"close") {handle.close();Undefined.instance}
                            function(obj,"info") {jsonValue(handle.info())}
                            function(obj,"receive") {values->
                                try {
                                    require(!listening) {"listen 期间不能调用 receive"}
                                    val timeout=values.firstOrNull()?.takeUnless {it is Undefined} ?: 10000
                                    require(timeout is Number && timeout.toDouble()%1==0.0 && timeout.toDouble() in 1.0..15000.0) {"timeout 须为 1–15000 的毫秒整数"}
                                    val start=System.nanoTime()
                                    val event=try {handle.poll(timeout.toLong())} finally {waitingNanos+=System.nanoTime()-start}
                                    checkRun();event?.let {jsonValue(it)}
                                } catch(e:Exception) {deviceFailure(e)}
                            }
                            function(obj,"listen") {
                                require(!listening && callbackDepth==0) {"不能在回调中或重复进入 listen"}
                                require(message!=null || kind in listOf("torch","dnd")) {"请先注册 onMessage"}
                                listening=true
                                try {
                                    while(true) {
                                        checkRun()
                                        var failure:DeviceFailure?=null
                                        val start=System.nanoTime()
                                        val event=try {handle.poll(100)} catch(e:DeviceFailure) {failure=e;null} finally {waitingNanos+=System.nanoTime()-start}
                                        checkRun()
                                        if(failure!=null) {
                                            if(!delivered) {
                                                delivered=true
                                                val e=failure!!
                                                if(e.details.optString("code")!="CLOSED") {if(error==null) deviceFailure(e);callback(error,jsonValue(e.details))}
                                                callback(closed,jsonValue(handle.info()))
                                            }
                                            break
                                        }
                                        if(event!=null) callback(message,jsonValue(event))
                                    }
                                    jsonValue(handle.info())
                                } finally {listening=false}
                            }
                            (when(kind) {"torch"->listOf("set");"nfc"->listOf("write");else->emptyList()}).forEach {name->function(obj,name) {values->
                                try {
                                    val arguments=org.json.JSONArray(Context.toString(NativeJSON.stringify(cx,scope,cx.newArray(scope,values),null,null)))
                                    val start=System.nanoTime()
                                    val result=try {handle.call(name,arguments)} finally {waitingNanos+=System.nanoTime()-start}
                                    checkRun()
                                    if(result is org.json.JSONObject || result is org.json.JSONArray) jsonValue(result) else js(result)
                                } catch(e:Exception) {deviceFailure(e)}
                            }}
                        }
                    } catch(e:Exception) {deviceFailure(e)}
                }}
                installHttp(objectValue(api,"http"),defaultHttpClient,true)
                val wifiApi=objectValue(api,"wifi")
                listOf("getHotspotCapabilities","getHotspotState","stopHotspot","checkHotspotLeaks").forEach {method->
                    function(wifiApi,method) {args->
                        require(args.isEmpty()) {"$method() 不接受参数"}
                        val start=System.nanoTime()
                        try {js((platform ?: error("当前环境不支持热点")).hotspot(method))} finally {waitingNanos+=System.nanoTime()-start}
                    }
                }
                function(wifiApi,"startHotspot") {args->
                    require(callbackDepth==0) {"不能在回调中启动热点"}
                    require(scopeHotspots.none {it.active}) {"当前脚本已启动热点"}
                    require(args.size<=1) {"请使用 startHotspot(options)"}
                    val options=args.firstOrNull()
                    require(options==null || options is Undefined || options is NativeObject) {"options 必须为对象"}
                    fun option(name:String):Any?=(options as? NativeObject)?.let {ScriptableObject.getProperty(it,name)}?.takeUnless {it===Scriptable.NOT_FOUND || it is Undefined}
                    fun textOption(name:String):String? {val value=option(name);require(value==null || value is CharSequence) {"$name 必须为字符串"};return value?.toString()}
                    val name=textOption("name")
                    val password=textOption("password")
                    require(name==null || (name.toByteArray(Charsets.UTF_8).size in 1..32 && name.none {it<' ' || it=='\u007f'})) {"name 须为 1–32 个 UTF-8 字节，不能包含控制字符"}
                    require(password==null || (password.length in 8..63 && password.all {it in ' '..'~'})) {"password 须为 8–63 个 ASCII 字符"}
                    val internet=option("internet") ?: false
                    require(internet is Boolean) {"internet 必须为 boolean"}
                    val callbacks=listOf("onStart","onStop","onError","onClientsChanged","onInternetChanged").associateWith {key->
                        val fn=option(key);require(fn==null || fn is org.mozilla.javascript.Function) {"$key 必须为函数"};fn as? org.mozilla.javascript.Function
                    }
                    fun invoke(key:String,value:Map<String,Any?>) {
                        val fn=callbacks[key] ?: return
                        callbackDepth++
                        try {fn.call(cx,scope,scope,arrayOf(js(value)))} finally {callbackDepth--}
                    }
                    var hotspot:HotspotSession?=null
                    var stopNotified=false
                    fun dispatch() {
                        if(control.stopped.get() && httpCloseDeadline==0L) return
                        val current=hotspot ?: return
                        while(true) {
                            val event=current.poll(0) ?: break
                            when(event.type) {
                                "stop"->if(!stopNotified){
                                    stopNotified=true
                                    val previousDeadline=httpCloseDeadline
                                    httpCloseDeadline=System.nanoTime()+250_000_000L
                                    try {invoke("onStop",event.values)} finally {httpCloseDeadline=previousDeadline}
                                }
                                "error"->{invoke("onError",event.values);throw HotspotFailure(event.values["code"].toString(),event.values["message"].toString())}
                                "clientsChanged"->invoke("onClientsChanged",event.values)
                                "internetChanged"->invoke("onInternetChanged",event.values)
                            }
                        }
                    }
                    val start=System.nanoTime()
                    val active=try {(platform ?: error("当前环境不支持热点")).openHotspot(HotspotOptions(name,password,internet),control)}
                    catch(e:Exception) {
                        checkRun()
                        invoke("onError",mapOf("code" to ((e as? HotspotFailure)?.code ?: "START_FAILED"),"message" to e.message,"fatal" to true))
                        throw e
                    } finally {waitingNanos+=System.nanoTime()-start}
                    hotspot=active;scopeHotspots.add(active)
                    val pump:()->Unit={dispatch()}
                    hotspotPumps.add(pump)
                    scopeHotspotClosers.add {
                        hotspotPumps.remove(pump)
                        active.close()
                        // Cleanup callbacks have the same bounded, log-only rules as HTTP onClose.
                        httpCloseDeadline=System.nanoTime()+250_000_000L
                        try {dispatch()} finally {httpCloseDeadline=0L}
                    }
                    try {invoke("onStart",active.info)} catch(e:Exception) {
                        active.close();invoke("onError",mapOf("code" to "CALLBACK_ERROR","message" to e.message,"fatal" to true));throw e
                    }
                    js(active.info)
                }
                val httpServerApi=objectValue(api,"httpServer")
                listOf("upload","chat").forEach {serverMethod->function(httpServerApi,serverMethod) {args->
                    val chatting=serverMethod=="chat"
                    require(callbackDepth==0) {"不能在回调中启动 HTTP 服务"}
                    require(args.size<=1) {"请使用 $serverMethod(options)"}
                    val options=args.firstOrNull()
                    require(options==null || options is Undefined || options is NativeObject) {"options 必须为对象"}
                    fun option(name:String):Any? = (options as? NativeObject)?.let {ScriptableObject.getProperty(it,name)}?.takeUnless {it===Scriptable.NOT_FOUND || it is Undefined}
                    fun integer(name:String,default:Int,min:Int,max:Int):Int {
                        val value=option(name) ?: return default
                        require(value is Number && value.toDouble().isFinite() && value.toDouble()%1==0.0 && value.toDouble() in min.toDouble()..max.toDouble()) {"$name 须为 $min–$max 的整数"}
                        return value.toInt()
                    }
                    val listenOption=option("listen")
                    require(listenOption==null || listenOption is CharSequence) {"listen 必须为 IPv4 字符串或 null"}
                    val listen=listenOption?.toString()
                    require(listen==null || HttpListenAddress.isValid(listen)) {"listen 必须为 IPv4 地址或末尾带 * 的前缀，如 10.*"}
                    val port=integer("port",8080,1024,65535)
                    val size=integer("maxFileSizeMB",100,1,1024)
                    val imageSize=if(chatting) integer("maxImageSizeMB",2,1,100) else 2
                    val tokenOption=option("token")
                    require(tokenOption==null || tokenOption is CharSequence) {"token 必须为字符串"}
                    val token=tokenOption?.toString().orEmpty()
                    require(token.length in 0..64 && token.all {it in '!'..'~'}) {"访问码须为空或 1–64 个非空格 ASCII 字符"}
                    fun callbackOption(name:String):org.mozilla.javascript.Function? {
                        val value=option(name)
                        require(value==null || value is org.mozilla.javascript.Function) {"$name 必须为函数"}
                        return value as? org.mozilla.javascript.Function
                    }
                    val nameOption=option("name")
                    require(nameOption==null || nameOption is CharSequence) {"name 必须为字符串"}
                    val serverName=nameOption?.toString()?.trim()?.takeIf {it.isNotEmpty()} ?: "And JS"
                    val onStart=callbackOption("onStart")
                    val onText=callbackOption("onText")
                    val onMessage=callbackOption("onMessage")
                    var serverObject:Scriptable?=null
                    val onProgress=callbackOption("onProgress")
                    val onError=callbackOption("onError")
                    val onClose=callbackOption("onClose")
                    var server:HttpServerSession?=null
                    var phase="start"
                    var closeReason="closed"
                    var closeMessage:String?=null
                    var errorHandlerFailed=false
                    fun callback(fn:org.mozilla.javascript.Function,arg:Any?) {
                        val previousPhase=phase
                        phase="callback"
                        callbackDepth++
                        try {fn.call(cx,scope,scope,if(chatting && fn===onMessage) arrayOf(arg,serverObject) else arrayOf(arg));phase=previousPhase} finally {callbackDepth--}
                    }
                    fun reportError(details:Map<String,Any?>) {
                        if(onError==null || errorHandlerFailed) return
                        try {callback(onError,js(details))} catch(e:Exception) {errorHandlerFailed=true;throw e}
                    }
                    fun progress(value:UploadProgress) {
                        try {
                        if(onProgress!=null) callback(onProgress,js(value.values()))
                        else if(!chatting) {
                            val message=when(value.status) {
                                "completed"->"${value.name}："+if(value.kind=="image") "已显示到日志" else "已保存到 Download/"
                                "failed"->"${value.name}：${value.error}"
                                else->"${value.name}：${value.values()["percent"]}%（${value.receivedBytes} / ${value.totalBytes} 字节）"
                            }
                            writeLog(LogEntry("info",message),value.id)
                        }
                        if(value.status=="failed") reportError(mapOf(
                            "code" to "UPLOAD_FAILED","message" to value.error,"phase" to "upload","fatal" to false,
                            "id" to value.id,"name" to value.name))
                        } finally {
                            if(!chatting && value.status=="completed" && value.kind=="file" && value.installUri!=null && value.name.endsWith(".apk",ignoreCase=true))
                                writeLog(LogEntry("apk",value.name,installUri=value.installUri),null)
                        }
                    }
                    try {
                        checkRun()
                        val host=platform ?: error("当前环境不支持 HTTP 服务")
                        val activeServer=if(chatting) host.openHttpChat(port,token,size,imageSize,control,serverName,listen) else host.openHttpServer(port,token,size,control,serverName,listen)
                        server=activeServer
                        activeServer.failure?.let {error(it)}
                        val details=linkedMapOf<String,Any?>("url" to activeServer.url,"port" to port,"token" to activeServer.token,
                            "status" to "listening","maxFileSizeMB" to size)
                        details["name"]=serverName
                        if(chatting) {details["sessionId"]=activeServer.sessionId;details["maxImageSizeMB"]=imageSize} else {details["directory"]="Download/"}
                        val handle=js(details) as ScriptableObject
                        serverObject=handle
                        if(chatting) function(handle,"send") {values->
                            require(values.size==1 && values[0] is CharSequence) {"请使用 server.send(text)"}
                            js(activeServer.sendSystem(values[0].toString()))
                        }
                        if(onStart!=null) callback(onStart,handle)
                        phase="server"
                        var lastProgress=0L
                        var lastId:String?=null
                        while(!activeServer.isClosed) {
                            checkRun()
                            hotspotPumps.toList().forEach {it()}
                            val waitStart=System.nanoTime()
                            val active=activeServer.progress
                            val waitMs=if(active==null) {if(hotspotPumps.isEmpty()) 0L else 200L} else if(lastId!=active.id) 1L
                                else ((lastProgress+1_000_000_000L-waitStart+999_999)/1_000_000).coerceIn(1L,1000L)
                            val event=try {activeServer.poll(waitMs)} finally {waitingNanos+=System.nanoTime()-waitStart}
                            checkRun()
                            when(event) {
                                is HttpServerEvent.Message-> {if(onMessage!=null) callback(onMessage,js(event.value))}
                                is HttpServerEvent.Text-> {
                                    try {
                                        if(onText!=null) callback(onText,event.text) else writeLog(LogEntry("info",event.text),null)
                                        event.reply.complete(true)
                                    } finally {event.reply.complete(false)}
                                }
                                is HttpServerEvent.Image-> {
                                    if(!event.reply.isDone) {
                                        try {
                                            val img=ScriptableObject.getProperty(log,"img") as org.mozilla.javascript.Function
                                            img.call(cx,scope,log,arrayOf(event.base64))
                                            event.reply.complete(null)
                                        } catch(e:RunAborted) {event.reply.complete("上传已停止");throw e}
                                        catch(e:Exception) {event.reply.complete(e.message ?: "图片日志显示失败")}
                                    }
                                }
                                is HttpServerEvent.Progress->progress(event.value)
                                HttpServerEvent.Wake,null->Unit
                            }
                            val current=activeServer.progress
                            val now=System.nanoTime()
                            if(current!=null && (lastId!=current.id || now-lastProgress>=1_000_000_000L)) {
                                progress(current);lastProgress=now;lastId=current.id
                            }
                        }
                        checkRun()
                        activeServer.failure?.let {error(it)}
                    } catch(e:RunAborted) {
                        closeReason="stopped";throw e
                    } catch(e:ScriptCallbackExited) {
                        closeReason="exit";throw e
                    } catch(e:Exception) {
                        if(control.stopped.get()) {closeReason="stopped";throw e}
                        closeReason="error";closeMessage=e.message ?: "HTTP 服务失败"
                        reportError(mapOf(
                            "code" to when(phase) {"start"->"START_FAILED";"callback"->"CALLBACK_ERROR";else->"SERVER_ERROR"},
                            "message" to closeMessage,"phase" to phase,"fatal" to true,"id" to null,"name" to null))
                        throw e
                    } finally {
                        try {server?.close();server?.awaitClosed()} finally {
                            if(onClose!=null) {
                                // Cleanup stays on the Rhino thread and cannot revive a cancelled run.
                                httpCloseDeadline=System.nanoTime()+250_000_000L
                                try {callback(onClose,js(mapOf("reason" to closeReason,"message" to closeMessage,
                                    "started" to (server!=null),"url" to server?.url)))}
                                catch(e:Exception) {writeLog(LogEntry("error","onClose：${e.message ?: "回调失败"}".take(2000)),null)}
                                finally {httpCloseDeadline=0L}
                            }
                        }
                    }
                    Undefined.instance
                }}

                function(log,"img") {args->
                    val host=platform ?: error("当前环境不支持图片日志")
                    require(args.size<=3) {"请使用 img(source, id, maxWidth)"}
                    val id=logId(args.getOrNull(1))
                    val index=logSlot(id)
                    if(!canLog(index)) return@function Undefined.instance
                    val old=index?.let {logs[it]}
                    require(logs.count {it.level=="img"}-(if(old?.level=="img") 1 else 0)<(if(lowMemory) 4 else 12)) {"图片日志数量已达到上限"}
                    val requestedWidth=args.getOrNull(2)
                    val percent=if(requestedWidth is CharSequence) {
                        val text=requestedWidth.toString().trim()
                        val value=if(text.endsWith("%")) text.dropLast(1).toFloatOrNull() else null
                        require(value!=null && value.isFinite() && value>0f && value<=100f) {"百分比宽度应大于 0% 且不超过 100%"}
                        value
                    } else null
                    val width=if(requestedWidth==null || requestedWidth is Undefined || percent!=null) (if(lowMemory) 160 else 240) else {
                        require(requestedWidth is Number && requestedWidth.toDouble().isFinite() && requestedWidth.toDouble()%1==0.0 && requestedWidth.toDouble() in 32.0..320.0) {"最大宽度应为 32–320 的整数或百分比字符串"}
                        requestedWidth.toInt().coerceAtMost(if(lowMemory) 160 else 320)
                    }
                    var input=args.firstOrNull()
                    if(input is NativeObject) {
                        val base64=ScriptableObject.getProperty(input,"base64")
                        val file=ScriptableObject.getProperty(input,"file")
                        input=when {base64 is CharSequence->base64;file is CharSequence->"app:/network/$file";else->error("网络响应需使用 base64 或 file 模式")}
                    }
                    require(input is CharSequence) {"请传入图片 URL、路径、Base64 或图片响应对象"}
                    var encoded=input.toString()
                    if(encoded.startsWith("https://") || encoded.startsWith("http://")) {
                        val start=System.nanoTime()
                        try {encoded=session.request(encoded,org.json.JSONObject().put("responseType","base64").put("maxBytes",2097152).put("throwOnHttpError",true)).getString("base64")}
                        catch(e:Exception) {if(control.stopped.get()) throw RunAborted(true);throw e}
                        finally {waitingNanos+=System.nanoTime()-start}
                    } else if(encoded.startsWith("app:/") || encoded.startsWith("shared:/")) {
                        val details=host.call("files","info",org.json.JSONArray().put(encoded),control) as Map<*,*>
                        require((details["size"] as Number).toLong()<=(2097152)) {"图片文件过大"}
                        encoded=host.call("files","read",org.json.JSONArray().put(encoded).put("base64"),control) as String
                    } else if(encoded.startsWith("data:")) {
                        val comma=encoded.indexOf(',')
                        require(comma>0 && encoded.substring(0,comma).startsWith("data:image/") && encoded.substring(0,comma).endsWith(";base64")) {"无效图片 Data URL"}
                        encoded=encoded.substring(comma+1)
                    }
                    checkRun()
                    val image=host.call("image","prepare",org.json.JSONArray().put(encoded),control) as Map<*,*>
                    checkRun()
                    val preview=image["base64"] as String
                    require(imageLogChars-(if(old?.level=="img") old.text.length else 0)+preview.length <= if(lowMemory) 1_400_000 else 5_600_000) {"图片日志总量已达上限"}
                    writeLog(LogEntry("img",preview,(image["ratio"] as Number).toFloat(),width,percent),id)
                    Undefined.instance
                }
                platform?.let {host->
                    listOf("notes" to listOf("get","set"),"clipboard" to listOf("get","set"),"cache" to listOf("get","set","remove"),"health" to listOf("status","current","summary","read"),"device" to listOf("info","battery","network","memory","storage","screen","power","sensors","vibrator","location","locationStatus","wifi","bluetooth","wifiList","bluetoothList"),"system" to listOf("getBrightness","setBrightness","getVolume","setVolume","openSettings","setAutoBrightness","getScreenTimeout","setScreenTimeout","getRotation","setRotation","vibrate","cancelVibration","notify","cancelNotification","speak","playAudio","toast","closeApp","returnToWatchFace","openRecents","openNotificationShade","openUrl","openApp","share"),"files" to listOf("read","write","append","exists","list","mkdir","delete","copy","move","info"),"permissions" to listOf("status")).forEach {(space,methods)->
                        val target=objectValue(api,space)
                        methods.forEach {name->function(target,name) {args->
                            val start=System.nanoTime()
                            val result=try {
                                val json=Context.toString(NativeJSON.stringify(cx,scope,cx.newArray(scope,args),null,null))
                                host.call(space,name,org.json.JSONArray(json),control)
                            } catch(e:Exception) {
                                if(control.stopped.get()) throw RunAborted(true)
                                if(e is kotlinx.coroutines.CancellationException) throw e
                                if(space!="system" || name !in listOf("returnToWatchFace","speak")) throw e
                                if(logs.size<=maxLogs) {
                                    logs.add(if(logs.size<maxLogs) LogEntry("error",("andjs.$space.$name："+(e.message ?: "调用失败")).take(2_000))
                                        else LogEntry("warn","日志已达到 $maxLogs 条上限"))
                                    onLogs(logs.toList())
                                }
                                false
                            }
                            finally {if(space=="health" || (space=="system" && name in listOf("speak","playAudio")) || (space=="device" && name in listOf("location","wifiList","bluetoothList"))) waitingNanos+=System.nanoTime()-start}
                            if(space=="system" && name=="closeApp") {
                                control.closeRequested.set(true)
                                control.stop()
                                throw RunAborted(true)
                            }
                            checkRun()
                            js(result)
                        }}
                    }
                }
                function(api,"text") {args->
                    val lines=args.firstOrNull()
                    require(lines is NativeArray) {"text 参数必须为数组"}
                    val limit=if(lowMemory) 256_000 else 1_000_000
                    require(lines.length<=limit) {"数组过长"}
                    val text=StringBuilder()
                    for(i in 0 until lines.length.toInt()) {
                        checkRun()
                        val value=ScriptableObject.getProperty(lines,i)
                        val part=if(value==null || value is Undefined || value===Scriptable.NOT_FOUND) "" else Context.toString(value)
                        require(text.length.toLong()+part.length+(if(i>0) 1 else 0)<=limit) {"拼接文本过长"}
                        if(i>0) text.append('\n')
                        text.append(part)
                    }
                    text.toString()
                }
                val script=objectValue(api,"script")
                function(script,"run") {args->
                    require(args.firstOrNull() is CharSequence) {"脚本 ID 必须为字符串"}
                    require(depth<8) {"脚本调用最多允许 8 层"}
                    val id=args[0].toString()
                    val document=control.loadScript?.invoke(id) ?: error("找不到脚本：$id；手表只能调用已同步的脚本")
                    val overrides=args.getOrElse(1) {Undefined.instance}
                    val entries=document.item.parameters.toMutableList()
                    if(overrides !is Undefined) {
                        require(overrides is NativeObject) {"params 必须为对象"}
                        overrides.ids.forEach {key->
                            val name=key.toString()
                            require(name.isNotBlank() && name.length<=200) {"参数键须为 1–200 字符"}
                            val value=if(key is Number) overrides.get(key.toInt(),overrides) else overrides.get(name,overrides)
                            val type=when(value) {is Boolean->"boolean";is CharSequence->"string";is Number->{require(value.toDouble().isFinite());"number"};is NativeArray,is NativeObject->"array";else->error("参数仅支持 boolean、string、number、array")}
                            val encoded=if(type=="array") Context.toString(NativeJSON.stringify(cx,scope,value,null,null)) else Context.toString(value)
                            require(encoded.length<=1_000_000) {"参数内容过大"}
                            entries.removeAll {it.key==name};entries.add(ScriptParameter(name,type,encoded))
                        }
                    }
                    // Child scopes share cancellation, time/memory budgets, global values and log limits.
                    execute(document.source,entries,{space,values->
                        if(space=="global") (control.persistVariables ?: error("无法保存全局变量"))(space,values)
                        else (control.saveScriptVariables ?: error("无法保存脚本参数"))(id,values)
                    },depth+1)
                }
                val util=objectValue(api,"util")
                function(util,"qr") {args->
                    require(args.firstOrNull() is CharSequence) {"二维码内容必须为字符串"}
                    (platform ?: error("当前环境不支持二维码")).call("util","qr",org.json.JSONArray().put(args[0].toString()),control)
                }
                function(util,"isJson") {args->
                    val value=args.firstOrNull()
                    if(value !is CharSequence) false else try {
                        NativeJSON.parse(cx,scope,value.toString(),Callable {_,_,_,values->values[1]})
                        true
                    } catch(e:RhinoException) {false}
                }
                function(util,"isUuid") {args->
                    val value=args.firstOrNull()
                    value is CharSequence && Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value)
                }
                function(util,"md5") {args->
                    val value=args.firstOrNull()
                    require(value is CharSequence) {"MD5 输入必须为字符串"}
                    java.security.MessageDigest.getInstance("MD5").digest(value.toString().toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
                }
                function(util,"time") {java.time.Instant.now().epochSecond.toDouble()}
                function(util,"date") {args->
                    val format=args.firstOrNull()
                    require(format==null || format is Undefined || format is CharSequence) {"时间格式必须为字符串"}
                    val pattern=if(format==null || format is Undefined) "yyyy-MM-dd HH:mm:ss" else format.toString()
                    require(pattern.length<=200) {"时间格式过长"}
                    val timestamp=args.getOrNull(1)
                    val instant=if(timestamp==null && args.size<2 || timestamp is Undefined) java.time.Instant.now() else {
                        require(timestamp is Number) {"时间戳必须为秒数"}
                        val seconds=timestamp.toDouble()
                        require(seconds.isFinite() && seconds%1==0.0 && kotlin.math.abs(seconds)<=8_640_000_000_000.0) {"时间戳须为有效的整数秒"}
                        java.time.Instant.ofEpochSecond(seconds.toLong())
                    }
                    java.time.format.DateTimeFormatter.ofPattern(pattern,java.util.Locale.getDefault())
                        .withZone(java.time.ZoneId.systemDefault()).format(instant)
                }
                function(util,"uuid") {UUID.randomUUID().toString()}
                val random by lazy {SecureRandom()}
                function(util,"randomInt") {args->
                    val min=Context.toNumber(args.getOrNull(0));val max=Context.toNumber(args.getOrNull(1))
                    require(min.isFinite() && max.isFinite() && min%1==0.0 && max%1==0.0 && min>=-1_000_000_000 && max<=1_000_000_000 && max>=min) {"范围须为 -10亿到10亿之间的整数，且最大值不小于最小值"}
                    min.toInt()+random.nextInt((max-min+1).toInt())
                }
                checkRun()
                try {
                    program(cx,code) { original ->
                        val before=heap.totalMemory()-heap.freeMemory()
                        preparing=true
                        preparationDeadline=System.nanoTime()+30_000_000_000L
                        try { ScriptCompatibility.transform(cx,original,::checkRun) }
                        finally {
                            // Compiler allocations are separate from the running script's heap budget.
                            baseline+=(heap.totalMemory()-heap.freeMemory()-before).coerceAtLeast(0L)
                            preparing=false
                        }
                    }.exec(cx,scope);checkRun()
                    do {
                        checkRun()
                        hotspotPumps.toList().forEach {it()}
                        if(scopeHotspots.none {it.active}) break
                        checkRun()
                        val waitStart=System.nanoTime()
                        try {Thread.sleep(100)} finally {waitingNanos+=System.nanoTime()-waitStart}
                    } while(true)
                } catch(_:ScriptExited) {}
                return returned
                } finally {
                    val closeStart=System.nanoTime()
                    try {scopeHotspotClosers.asReversed().forEach {runCatching {it()}};scopeHotspots.forEach {runCatching {it.close()}};scopeBle.forEach {runCatching {it.close()}};scopeSockets.forEach {it.finish()}} finally {waitingNanos+=System.nanoTime()-closeStart}
                }
                }
                execute(source,parameters,control.persistVariables,0)
            })
            RunResult(output,logs.toList(),elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:ScriptCallbackExited) {
            RunResult(output,logs.toList(),elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:ScriptExited) {
            RunResult(output,logs.toList(),elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:RunAborted) {
            RunResult(output,logs.toList(),null,e.cancelled,(System.nanoTime()-started)/1_000_000)
        } catch(e:RhinoException) {
            RunResult(output,logs.toList(),"${e.sourceName() ?: "main.js"}:${e.lineNumber()}\n${e.details()}",elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:Exception) {
            RunResult(output,logs.toList(),e.message ?: "运行失败",elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:StackOverflowError) {
            RunResult(output,logs.toList(),"调用层级过深",elapsedMs=(System.nanoTime()-started)/1_000_000)
        } finally {
            try {try {if(deviceSession.isInitialized()) deviceSession.value.close()} finally {try {if(bleSession.isInitialized()) bleSession.value.close()} finally {if(socketManager.isInitialized()) socketManager.value.dispose()}}}
            finally {try {httpSessions.forEach {it.close()}} finally {io.todu.andjs.data.AppCache.leave(cacheTicket)}}
        }
    }
}
