package io.todu.andjs.runtime

import io.todu.andjs.domain.ScriptParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.javascript.*
import java.util.concurrent.atomic.AtomicBoolean
import java.security.SecureRandom
import java.util.UUID

class RunControl(val persistVariables:((String,List<ScriptParameter>)->Unit)?=null, val loadScript:((String)->io.todu.andjs.domain.ScriptDocument?)?=null, val saveScriptVariables:((String,List<ScriptParameter>)->Unit)?=null, val onInteraction:((ScriptInteraction?)->Unit)?=null) {
    val closeRequested = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)
    private val activeStop=java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    fun stop() { stopped.set(true);activeStop.get()?.invoke() }
    fun registerStop(action:()->Unit):()->Unit {
        activeStop.set(action)
        if(stopped.get()) action()
        return {activeStop.compareAndSet(action,null);Unit}
    }
}
data class LogEntry(val level:String,val text:String,val imageRatio:Float=1f,val imageWidth:Int=240,val imageWidthPercent:Float?=null)
data class RunResult(val output:String, val logs:List<LogEntry>, val error:String?=null, val stopped:Boolean=false, val elapsedMs:Long=0)
interface ScriptRunner {
    suspend fun run(source:String, parameters:List<ScriptParameter>, globals:List<ScriptParameter>, control:RunControl, onLogs:(List<LogEntry>)->Unit = {}):RunResult
}
private class ScriptExited: Error()
private class RunAborted(val cancelled:Boolean): Error()

/** Each invocation has an independent safe scope; Java reflection and host objects are not exposed. */
class RhinoScriptRunner(private val timeoutMs:Long=15_000,private val networkRoot:java.io.File=java.io.File(System.getProperty("java.io.tmpdir"),"andjs-network"),private val platform:PlatformApi?=null,private val lowMemory:Boolean=false) : ScriptRunner {
    override suspend fun run(source:String, parameters:List<ScriptParameter>, globals:List<ScriptParameter>, control:RunControl, onLogs:(List<LogEntry>)->Unit):RunResult = withContext(Dispatchers.Default) {
        val started=System.nanoTime()
        val maxLogs=if(lowMemory) 40 else 200
        val maxOutput=if(lowMemory) 8000 else 32000
        val heap=Runtime.getRuntime()
        val baseline=heap.totalMemory()-heap.freeMemory()
        val logs=mutableListOf<LogEntry>()
        var output=""
        var imageLogChars=0
        var waitingNanos=0L
        var interactionWaitingNanos=0L
        val httpSessions=mutableListOf<HttpSession>()
        var globalValues=globals.toList()
        fun checkRun() {
            if(control.stopped.get()) throw RunAborted(true)
            if(lowMemory && (System.nanoTime()-started-interactionWaitingNanos)/1_000_000>60_000) throw Context.reportRuntimeError("手表运行已超过 60 秒，已停止")
            if(lowMemory && heap.totalMemory()-heap.freeMemory()-baseline>16L*1024*1024) throw Context.reportRuntimeError("脚本内存占用过高，已停止")
            if((System.nanoTime()-started-waitingNanos)/1_000_000 >= timeoutMs) throw RunAborted(false)
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
        try {
            require(source.length<=if(lowMemory) 256_000 else 1_000_000) {"脚本超过当前设备大小限制"}
            factory.call(ContextAction<Unit> {cx ->
                val session by lazy {HttpSession(networkRoot,control,lowMemory).also {httpSessions.add(it)}}
                fun execute(code:String, initialParameters:List<ScriptParameter>, persist:((String,List<ScriptParameter>)->Unit)?, depth:Int):Any? {
                require(depth<=8) {"脚本调用最多允许 8 层"}
                require(code.length<=if(lowMemory) 256_000 else 1_000_000) {"脚本超过当前设备大小限制"}
                var returned:Any?=Undefined.instance
                val scope=cx.initSafeStandardObjects()
                fun objectValue(parent:Scriptable, key:String):Scriptable {
                    val obj=cx.newObject(scope); ScriptableObject.putProperty(parent,key,obj);return obj
                }
                fun function(parent:Scriptable,key:String,body:(Array<out Any?>)->Any?) {
                    val fn=object:BaseFunction() {
                        override fun call(context:Context, scope:Scriptable, thisObj:Scriptable, args:Array<out Any?>):Any? {
                            checkRun()
                            return try {body(args)} catch(e:Exception) {if(e is RhinoException) throw e;throw Context.reportRuntimeError(e.message ?: "调用失败")}
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
                            is NativeArray -> {
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
                listOf("info","warn","error").forEach {level->function(log,level) {args->
                    val message=args.joinToString(" ") {display(it)}
                    if(logs.size<maxLogs+1) {
                        if(logs.size<maxLogs) logs.add(LogEntry(level,message.take(2_000)))
                        else logs.add(LogEntry("warn","日志已达到 $maxLogs 条上限"))
                        onLogs(logs.toList())
                    }
                    Undefined.instance
                }}
                val info=ScriptableObject.getProperty(log,"info")
                ScriptableObject.putProperty(objectValue(scope,"console"),"log",info)
                function(scope,"exit") {args->
                    returned=args.getOrElse(0) {Undefined.instance}
                    if(depth==0) output=when {
                        args.isEmpty() || args[0] is Undefined -> ""
                        args[0] is Boolean -> if(args[0] == true) "执行成功" else "执行失败"
                        else -> display(args[0])
                    }
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
                val http=objectValue(api,"http")
                fun network(args:Array<out Any?>,method:String?=null,download:Boolean=false):Any? {
                    require(args.firstOrNull() is CharSequence) {"URL 必须为字符串"}
                    val options=if(args.size<2 || args[1] is Undefined) org.json.JSONObject() else {
                        require(args[1] is NativeObject) {"options 必须为对象"}
                        org.json.JSONObject(Context.toString(NativeJSON.stringify(cx,scope,args[1],null,null)))
                    }
                    if(method!=null) options.put("method",method)
                    if(download) options.put("responseType","file")
                    val start=System.nanoTime()
                    val response=try {session.request(args[0].toString(),options)}
                    catch(e:Exception) {
                        if(control.stopped.get()) throw RunAborted(true)
                        throw Context.reportRuntimeError("网络请求失败："+(e.message ?: "连接错误"))
                    } finally {waitingNanos+=System.nanoTime()-start}
                    checkRun()
                    val result=NativeJSON.parse(cx,scope,response.toString(),Callable {_,_,_,v->v[1]}) as Scriptable
                    val responseType=options.optString("responseType","auto")
                    if(responseType=="json" || responseType=="auto") {
                        val text=response.optString("text").removePrefix("\uFEFF").trim()
                        val data=if(responseType=="json") {
                            if(text.isEmpty()) null else NativeJSON.parse(cx,scope,text,Callable {_,_,_,v->v[1]}).also {ScriptableObject.putProperty(result,"isJson",response.optBoolean("ok"))}
                        } else {
                            try { NativeJSON.parse(cx,scope,text,Callable {_,_,_,v->v[1]}).also {ScriptableObject.putProperty(result,"isJson",response.optBoolean("ok"))} }
                            catch(_:RhinoException) {response.optString("text")}
                        }
                        ScriptableObject.putProperty(result,"data",data)
                    }
                    return result
                }
                function(http,"request") {network(it)}
                listOf("get","post","put","patch","delete","head","options").forEach {method->function(http,method) {network(it,method.uppercase())}}
                function(http,"download") {network(it,"GET",true)}
                function(http,"clearCookies") {session.clearCookies();Undefined.instance}
                function(log,"img") {args->
                    val host=platform ?: error("当前环境不支持图片日志")
                    if(logs.size>=maxLogs) {
                        if(logs.size==maxLogs) {logs.add(LogEntry("warn","日志已达到 $maxLogs 条上限"));onLogs(logs.toList())}
                        return@function Undefined.instance
                    }
                    require(logs.count {it.level=="img"}<(if(lowMemory) 4 else 12)) {"图片日志数量已达到上限"}
                    val requestedWidth=args.getOrNull(1)
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
                        try {encoded=session.request(encoded,org.json.JSONObject().put("responseType","base64").put("maxBytes",if(lowMemory) 524288 else 2097152).put("throwOnHttpError",true)).getString("base64")}
                        catch(e:Exception) {if(control.stopped.get()) throw RunAborted(true);throw e}
                        finally {waitingNanos+=System.nanoTime()-start}
                    } else if(encoded.startsWith("app:/") || encoded.startsWith("shared:/")) {
                        val details=host.call("files","info",org.json.JSONArray().put(encoded),control) as Map<*,*>
                        require((details["size"] as Number).toLong()<=(if(lowMemory) 524288 else 2097152)) {"图片文件过大"}
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
                    require(imageLogChars+preview.length <= if(lowMemory) 1_400_000 else 5_600_000) {"图片日志总量已达上限"}
                    imageLogChars+=preview.length
                    logs.add(LogEntry("img",preview,(image["ratio"] as Number).toFloat(),width,percent))
                    onLogs(logs.toList())
                    Undefined.instance
                }
                platform?.let {host->
                    listOf("clipboard" to listOf("get","set"),"cache" to listOf("get","set","remove"),"health" to listOf("status","current","summary","read"),"device" to listOf("info","battery","network","memory","storage","screen","power","sensors","vibrator","location","locationStatus","wifi","bluetooth"),"system" to listOf("getBrightness","setBrightness","getVolume","setVolume","openSettings","setAutoBrightness","getScreenTimeout","setScreenTimeout","getRotation","setRotation","vibrate","cancelVibration","notify","cancelNotification","speak","playAudio","toast","returnToWatchFace","openRecents","openNotificationShade","openUrl","share"),"files" to listOf("read","write","append","exists","list","mkdir","delete","copy","move","info"),"permissions" to listOf("status")).forEach {(space,methods)->
                        val target=objectValue(api,space)
                        methods.forEach {name->function(target,name) {args->
                            val json=Context.toString(NativeJSON.stringify(cx,scope,cx.newArray(scope,args),null,null))
                            val start=System.nanoTime()
                            val result=try {host.call(space,name,org.json.JSONArray(json),control)}
                            catch(e:Exception) {if(control.stopped.get()) throw RunAborted(true);throw e}
                            finally {if(space=="health" || (space=="system" && name in listOf("speak","playAudio")) || (space=="device" && name=="location")) waitingNanos+=System.nanoTime()-start}
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
                            val type=when(value) {is Boolean->"boolean";is CharSequence->"string";is Number->{require(value.toDouble().isFinite());"number"};is NativeArray->"array";else->error("参数仅支持 boolean、string、number、array")}
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
                val random=SecureRandom()
                function(util,"randomInt") {args->
                    val min=Context.toNumber(args.getOrNull(0));val max=Context.toNumber(args.getOrNull(1))
                    require(min.isFinite() && max.isFinite() && min%1==0.0 && max%1==0.0 && min>=-1_000_000_000 && max<=1_000_000_000 && max>=min) {"范围须为 -10亿到10亿之间的整数，且最大值不小于最小值"}
                    min.toInt()+random.nextInt((max-min+1).toInt())
                }
                checkRun()
                try {cx.evaluateString(scope,code,"main.js",1,null);checkRun()} catch(_:ScriptExited) {}
                return returned
                }
                execute(source,parameters,control.persistVariables,0)
            })
            RunResult(output,logs.toList(),elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:ScriptExited) {
            RunResult(output,logs.toList(),elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:RunAborted) {
            RunResult(output,logs.toList(),if(e.cancelled) null else "运行超过 ${timeoutMs/1000} 秒，已停止",e.cancelled,(System.nanoTime()-started)/1_000_000)
        } catch(e:RhinoException) {
            RunResult(output,logs.toList(),"${e.sourceName() ?: "main.js"}:${e.lineNumber()}\n${e.details()}",elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:Exception) {
            RunResult(output,logs.toList(),e.message ?: "运行失败",elapsedMs=(System.nanoTime()-started)/1_000_000)
        } catch(e:StackOverflowError) {
            RunResult(output,logs.toList(),"调用层级过深",elapsedMs=(System.nanoTime()-started)/1_000_000)
        } finally {httpSessions.forEach {it.close()}}
    }
}
