package io.todu.andjs.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Playback is scoped to one blocking call; all Android player operations run on the main thread. */
object ScriptAudio {
    fun play(context:Context,method:String,args:JSONArray,control:RunControl,resolve:(String)->File):Boolean {
        val source=args.get(0)
        require(source is String && source.isNotBlank()) {"文字或音频路径不能为空"}
        val volume=if(args.length()<2) 1.0 else (args.get(1) as? Number)?.toDouble() ?: error("音量必须为 0–1 的数字")
        require(volume.isFinite() && volume in 0.0..1.0) {"音量必须为 0–1 的数字"}
        val file=if(method=="playAudio") resolve(source).also {require(it.isFile) {"音频文件不存在"}} else null
        if(file==null) require(source.length<=TextToSpeech.getMaxSpeechInputLength()) {"朗读文字超过系统长度限制"}
        val handler=Handler(Looper.getMainLooper())
        val done=CountDownLatch(1)
        val closed=AtomicBoolean(false)
        val failure=AtomicReference<String?>(null)
        var player:MediaPlayer?=null
        var speech:TextToSpeech?=null
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if(file==null) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build()
        fun cleanup() {
            player?.let {runCatching {it.release()}};player=null
            speech?.let {runCatching {it.stop()};runCatching {it.shutdown()}};speech=null
        }
        fun finish(error:String?=null) {
            if(closed.compareAndSet(false,true)) {
                failure.set(error)
                handler.post {cleanup();done.countDown()}
            }
        }
        val unregister=control.registerStop {finish("播放已停止")}
        try {
            handler.post {
                if(!closed.get()) try {
                    if(file!=null) {
                        player=MediaPlayer().also {p->
                            p.setAudioAttributes(attributes)
                            p.setVolume(volume.toFloat(),volume.toFloat())
                            p.setOnCompletionListener {finish()}
                            p.setOnErrorListener {_,what,extra->finish("音频播放失败 ($what/$extra)");true}
                            p.setOnPreparedListener {if(!closed.get()) it.start()}
                            p.setDataSource(file.path)
                            p.prepareAsync()
                        }
                    } else {
                        speech=TextToSpeech(context.applicationContext) {status->
                            handler.post {
                                if(!closed.get()) try {
                                    check(status==TextToSpeech.SUCCESS) {"无法初始化系统朗读引擎，请检查语音引擎和语言包"}
                                    val tts=checkNotNull(speech)
                                    check(tts.setAudioAttributes(attributes)==TextToSpeech.SUCCESS) {"无法设置朗读音频"}
                                    tts.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                                        override fun onStart(id:String?) {}
                                        override fun onDone(id:String?) {finish()}
                                        @Deprecated("Android callback") override fun onError(id:String?) {finish("系统朗读失败，请检查语言包")}
                                        override fun onError(id:String?,code:Int) {finish("系统朗读失败 ($code)")}
                                    })
                                    val params=Bundle().apply {putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume.toFloat())}
                                    check(tts.speak(source,TextToSpeech.QUEUE_FLUSH,params,"andjs-speech")==TextToSpeech.SUCCESS) {"系统无法朗读此文字"}
                                } catch(e:Exception) {finish(e.message ?: "系统朗读失败")}
                            }
                        }
                    }
                } catch(e:Exception) {finish(e.message ?: "音频播放失败")}
            }
            check(done.await(5,TimeUnit.MINUTES)) {"音频播放超时（最多 5 分钟）"}
            failure.get()?.let {error(it)}
            return true
        } finally {
            finish()
            unregister()
        }
    }
}
