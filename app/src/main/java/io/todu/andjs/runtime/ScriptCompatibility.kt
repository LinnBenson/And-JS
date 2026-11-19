package io.todu.andjs.runtime

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream

/** Lower lexical bindings without losing per-iteration closures or const assignment errors. */
internal object ScriptCompatibility {
    private val lexical = Regex("\\b(?:let|const)\\b")
    private val lock = ReentrantLock()
    private var cached = WeakReference<ScriptableObject>(null)

    fun transform(cx: Context, source: String, check: () -> Unit): String {
        if (!lexical.containsMatchIn(source)) return source
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) check()
        val previousDepth = cx.maximumInterpreterStackDepth
        try {
            cx.maximumInterpreterStackDepth = maxOf(previousDepth, 1024)
            check()
            val scope = cached.get() ?: cx.initSafeStandardObjects().also {
                cx.evaluateString(it, "var console={log:function(){},warn:function(){},error:function(){}};", "compatibility", 1, null)
                val stream = checkNotNull(javaClass.getResourceAsStream("babel-7.28.5.min.js.gz")) {
                    "缺少 JavaScript 兼容组件"
                }
                GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { reader ->
                    cx.evaluateReader(it, reader, "babel.js", 1, null)
                }
                cx.evaluateString(it, """
                    function prepareSource(source) {
                        return Babel.transform(source, {
                            filename: 'main.js', configFile: false, babelrc: false,
                            sourceType: 'script', retainLines: true, comments: true,
                            plugins: ['transform-destructuring', 'transform-for-of',
                                'transform-block-scoped-functions',
                                'transform-block-scoping']
                        }).code;
                    }
                """.trimIndent(), "compatibility", 1, null)
                cached = WeakReference(it)
            }
            val function = ScriptableObject.getProperty(scope, "prepareSource") as Function
            val output = Context.toString(function.call(cx, scope, scope, arrayOf(source)))
            check()
            return output
        } finally {
            cx.maximumInterpreterStackDepth = previousDepth
            lock.unlock()
        }
    }
}
