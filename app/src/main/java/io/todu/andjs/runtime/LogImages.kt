package io.todu.andjs.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

object LogImages {
    fun prepare(encoded:String,watch:Boolean):Map<String,Any> {
        val limit=if(watch) 512*1024 else 2*1024*1024
        require(encoded.length<=((limit+2)/3)*4) {"图片数据过大"}
        val bytes=try {Base64.getDecoder().decode(encoded)} catch(_:IllegalArgumentException) {error("无效 Base64 图片")}
        require(bytes.size<=limit) {"图片数据过大"}
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000) {"图片无效或尺寸过大"}
        val edge=if(watch) 320 else 640
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>edge) sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: error("无法解码图片")
        try {
            val output=ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)) {"无法生成图片预览"}
            require(output.size()<=(if(watch) 192*1024 else 768*1024)) {"图片预览过大，请缩小图片"}
            return mapOf("base64" to Base64.getEncoder().encodeToString(output.toByteArray()),"ratio" to bitmap.width.toDouble()/bitmap.height)
        } finally {bitmap.recycle()}
    }
}
