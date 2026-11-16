package io.todu.andjs.data

import android.content.Context
import io.todu.andjs.runtime.RunControl
import io.todu.andjs.runtime.ScriptCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A process-wide barrier keeps cache deletion away from running scripts, including overlays. */
object AppCache {
    private val gate=Mutex()
    private val maintenance=Mutex()
    private val active=mutableMapOf<CompletableDeferred<Unit>,RunControl>()
    private val mutableEpoch=MutableStateFlow(0L)
    val epoch=mutableEpoch.asStateFlow()
    private val mutableBusy=MutableStateFlow(false)
    val busy=mutableBusy.asStateFlow()

    suspend fun enter(control:RunControl):CompletableDeferred<Unit>?=gate.withLock {
        if(mutableBusy.value || control.cacheEpoch<mutableEpoch.value) {
            control.closeRequested.set(true);control.stop();null
        } else CompletableDeferred<Unit>().also {active[it]=control}
    }
    suspend fun leave(ticket:CompletableDeferred<Unit>)=withContext(NonCancellable) {
        gate.withLock {active.remove(ticket);ticket.complete(Unit);Unit}
    }
    suspend fun clear(context:Context)=maintenance.withLock {
        val running=gate.withLock {
            mutableBusy.value=true
            mutableEpoch.value++
            active.toMap()
        }
        try {
            running.values.forEach {it.closeRequested.set(true);it.stop()}
            check(withTimeoutOrNull(5000) {running.keys.forEach {it.await()};true}==true) {"任务尚未结束，请稍后再清理"}
            withContext(Dispatchers.IO) {
                val app=context.applicationContext
                // User files, scripts, preferences, notes and sync snapshots are deliberately excluded.
                val roots=listOfNotNull(app.cacheDir,app.codeCacheDir)+app.externalCacheDirs.filterNotNull()
                roots.distinctBy {it.absolutePath}.forEach {clearDirectory(it)}
                ScriptCache(File(app.filesDir,"script-cache")).clear()
                ScriptCache(File(app.filesDir,"watch-preview/script-cache")).clear()
                listOf("user/network","watch-preview/user/network").forEach {relative->
                    val directory=File(app.filesDir,relative)
                    if(directory.exists()) {
                        val children=directory.listFiles() ?: error("无法读取临时下载目录")
                        children.filter {it.name.matches(Regex("andjs-temp-[0-9a-f-]{36}\\.bin|andjs-part-[0-9]+\\.tmp"))}
                            .forEach {Files.deleteIfExists(it.toPath())}
                    }
                }
            }
        } finally {withContext(NonCancellable) {gate.withLock {mutableBusy.value=false}}}
    }
    private fun clearDirectory(root:File) {
        if(!root.exists()) return
        val children=root.listFiles() ?: error("无法读取缓存目录")
        children.forEach {child->
            // walkFileTree does not follow symbolic links into user data.
            Files.walkFileTree(child.toPath(),object:SimpleFileVisitor<Path>() {
                override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult {
                    Files.deleteIfExists(file);return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir:Path,error:java.io.IOException?):FileVisitResult {
                    if(error!=null) throw error
                    Files.deleteIfExists(dir);return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
