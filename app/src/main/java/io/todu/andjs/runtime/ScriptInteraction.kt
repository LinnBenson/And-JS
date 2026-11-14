package io.todu.andjs.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScriptInteraction(val kind:String,val title:String,val options:List<String> = emptyList(),val initial:String="",val content:String="") {
    private val completed=AtomicBoolean(false)
    private val latch=CountDownLatch(1)
    @Volatile private var answer:Any?=null
    fun respond(value:Any?) {
        if(completed.compareAndSet(false,true)) {answer=value;latch.countDown()}
    }
    fun await(check:()->Unit):Any? {
        check()
        latch.await()
        check()
        return answer
    }
}
