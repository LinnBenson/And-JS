package io.todu.andjs.runtime

import okhttp3.Dns
import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** TCP connect checks only: no payload, banner reads, retries or background work. */
internal object TcpPortScanner {
    private val slots=Semaphore(16)
    fun scan(options:JSONObject,control:RunControl,watch:Boolean):JSONObject {
        fun checkRun() {if(control.stopped.get() || Thread.currentThread().isInterrupted) throw deviceError("CANCELLED","扫描已停止")}
        fun integer(value:Any,min:Int,max:Int,name:String):Int {
            require(value is Number && value.toDouble().isFinite() && value.toDouble()%1==0.0 && value.toDouble() in min.toDouble()..max.toDouble()) {"$name 须为 $min–$max 的整数"}
            return value.toInt()
        }
        checkRun()
        val host=options.get("host").also {require(it is String) {"host 须为 IP 地址或域名"}}.toString().trim()
        require(host.isNotEmpty() && host.length<=253 && host.none {it.isWhitespace() || it in "/?#@"}) {"host 须为单个 IP 地址或域名，不含 URL 或端口"}
        val portsValue=options.getJSONArray("ports")
        require(portsValue.length() in 1..(if(watch) 256 else 1024)) {"ports 须含 1–${if(watch) 256 else 1024} 个端口"}
        val ports=(0 until portsValue.length()).map {integer(portsValue.get(it),1,65535,"port")}.distinct()
        val timeout=integer(options.opt("timeout") ?: 800,1,10000,"timeout")
        val concurrency=integer(options.opt("concurrency") ?: if(watch) 4 else 8,1,if(watch) 4 else 16,"concurrency").coerceAtMost(ports.size)
        val started=System.nanoTime()
        val resolver=InterruptibleDns(Dns.SYSTEM,control)
        val address=try {
            resolver.begin(System.nanoTime()+TimeUnit.SECONDS.toNanos(5))
            resolver.lookup(host).firstOrNull() ?: throw UnknownHostException(host)
        } catch(e:Exception) {
            checkRun()
            throw deviceError(if(e is SocketTimeoutException) "DNS_TIMEOUT" else "DNS_ERROR",e.message ?: "域名解析失败")
        } finally {resolver.end()}
        checkRun()
        if(!slots.tryAcquire(concurrency)) throw deviceError("BUSY","端口扫描繁忙，请稍后重试")
        try {
            val executor=Executors.newFixedThreadPool(concurrency) {r->Thread(r,"AndJS-TCP-scan").apply {isDaemon=true}}
            val lock=Any()
            val sockets=mutableSetOf<Socket>()
            var closed=false
            fun shutdown() {
                synchronized(lock) {closed=true;sockets.forEach {runCatching {it.close()}};sockets.clear()}
                executor.shutdownNow()
            }
            val release=control.registerStop {shutdown()}
            val next=AtomicInteger()
            val results=arrayOfNulls<JSONObject>(ports.size)
            try {
                val workers=(0 until concurrency).map {
                    executor.submit {
                        while(true) {
                            checkRun()
                            val index=next.getAndIncrement()
                            if(index>=ports.size) break
                            val port=ports[index]
                            val start=System.nanoTime()
                            val socket=Socket()
                            val item=JSONObject().put("port",port)
                            try {
                                synchronized(lock) {
                                    if(closed || control.stopped.get()) {socket.close();throw deviceError("CANCELLED","扫描已停止")}
                                    sockets.add(socket)
                                }
                                socket.connect(InetSocketAddress(address,port),timeout)
                                item.put("status","open")
                            } catch(e:Exception) {
                                checkRun()
                                val refused=generateSequence<Throwable>(e) {it.cause}.any {
                                    (it is android.system.ErrnoException && it.errno==android.system.OsConstants.ECONNREFUSED) ||
                                        (it is ConnectException && (it.message?.contains("refused",true)==true || it.message?.contains("ECONNREFUSED")==true))
                                }
                                item.put("status",when {e is SocketTimeoutException->"timeout";refused->"closed";e is NoRouteToHostException->"unreachable";else->"error"})
                                item.put("message",(e.message ?: "连接失败").take(300))
                            } finally {runCatching {socket.close()};synchronized(lock) {sockets.remove(socket)}}
                            checkRun()
                            results[index]=item.put("elapsedMs",(System.nanoTime()-start)/1000000)
                        }
                    }
                }
                workers.forEach {future->
                    while(true) {
                        checkRun()
                        try {future.get(100,TimeUnit.MILLISECONDS);break} catch(_:TimeoutException) {}
                    }
                }
                checkRun()
                return JSONObject().put("host",host).put("address",address.hostAddress).put("items",JSONArray(results.toList()))
                    .put("openPorts",JSONArray(results.filter {it?.optString("status")=="open"}.map {it!!.getInt("port")}))
                    .put("elapsedMs",(System.nanoTime()-started)/1000000)
            } catch(e:Exception) {
                checkRun()
                val cause=(e as? ExecutionException)?.cause ?: e
                if(cause is DeviceFailure) throw cause
                throw deviceError("SCAN_ERROR",cause.message ?: "端口扫描失败")
            } finally {shutdown();release()}
        } finally {slots.release(concurrency)}
    }
}
