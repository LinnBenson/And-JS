package io.todu.andjs.runtime

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HotspotFailure(val code:String,override val message:String):Exception(message)
data class HotspotOptions(val name:String?=null,val password:String?=null,val internet:Boolean=false)
data class HotspotEvent(val type:String,val values:Map<String,Any?>)
interface HotspotSession:Closeable {
    val id:String
    val active:Boolean
    val info:Map<String,Any?>
    fun poll(timeoutMs:Long):HotspotEvent?
}

/** The cancellation tombstone outlives a script until a late platform result is released. */
internal class HotspotRequest(val control:RunControl,val internet:Boolean,private val finished:(HotspotRequest)->Unit={}):HotspotSession {
    override val id=UUID.randomUUID().toString()
    private val events=LinkedBlockingQueue<HotspotEvent>()
    private var release:(()->Unit)?=null
    private var cancelPending:(()->Unit)?=null
    private var unregister:(()->Unit)?=null
    private var result:Map<String,Any?> = emptyMap()
    private var cancelled=false
    private var terminal=false
    private var stopEvent=false
    private var error:HotspotFailure?=null
    private val createdAt=System.currentTimeMillis()
    @Volatile var state="starting";private set
    @Volatile var cleanupError:String?=null;private set
    @Volatile var pending=true;private set
    override val active:Boolean get()=synchronized(this){!cancelled && !terminal}
    override val info:Map<String,Any?> get()=synchronized(this){result+mapOf("requestId" to id,"status" to state,"internet" to internet)}
    @Synchronized fun attach() {
        val remove=control.registerStop {close()}
        if(cancelled || terminal) remove() else unregister=remove
    }
    @Synchronized fun setCancel(action:()->Unit) {cancelPending=action;if(cancelled && pending) attempt(action)}
    @Synchronized fun started(details:Map<String,Any?>,close:()->Unit) {
        pending=false;cancelPending=null;release=close;result=details
        if(cancelled || terminal) {releaseHeld();return}
        state="started"
    }
    @Synchronized fun failed(failure:HotspotFailure) {
        pending=false;cancelPending=null
        if(!cancelled) {error=failure;events.offer(HotspotEvent("error",mapOf("code" to failure.code,"message" to failure.message,"fatal" to true)))}
        terminal=true;state="stopped";unregister?.invoke();unregister=null;finished(this)
    }
    @Synchronized fun platformStopped() {
        pending=false;release=null;cancelPending=null;terminal=true;state="stopped";cleanupError=null
        notifyStop("system");unregister?.invoke();unregister=null;finished(this)
    }
    @Synchronized fun failure():HotspotFailure?=error
    @Synchronized fun emit(type:String,values:Map<String,Any?>) {if(active) {if(events.size>=32) events.poll();events.offer(HotspotEvent(type,values))}}
    private fun attempt(action:()->Unit):Boolean=try {action();cleanupError=null;true} catch(e:Exception){cleanupError=e.message ?: "释放失败";false}
    private fun releaseHeld() {
        val action=release ?: return
        if(attempt(action)) {release=null;state="stopped";terminal=true;notifyStop("released");finished(this)}
    }
    private fun notifyStop(reason:String) {if(!stopEvent){stopEvent=true;events.offer(HotspotEvent("stop",mapOf("reason" to reason,"requestId" to id,"released" to true,"deviceStopped" to null)))}}
    @Synchronized override fun close() {
        cancelled=true;state=if(terminal) "stopped" else "stopping"
        unregister?.invoke();unregister=null
        if(release!=null) releaseHeld() else if(pending) cancelPending?.let {attempt(it)}
        else {state="stopped";terminal=true;finished(this)}
    }
    @Synchronized fun snapshot():Map<String,Any?> = mapOf("requestId" to id,"status" to state,"internet" to internet,
        "pending" to pending,"cancelled" to cancelled,"ownerStopped" to control.stopped.get(),"hasReservation" to (release!=null),
        "cleanupError" to cleanupError,"ageMs" to System.currentTimeMillis()-createdAt,
        "residual" to ((cancelled || control.stopped.get() || terminal) && (pending || release!=null)),"finished" to (!pending && release==null && terminal))
    override fun poll(timeoutMs:Long):HotspotEvent?=if(timeoutMs<=0) events.poll() else events.poll(timeoutMs,TimeUnit.MILLISECONDS)
}
