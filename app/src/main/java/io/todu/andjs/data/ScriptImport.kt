package io.todu.andjs.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

data class ImportedScript(val name:String,val source:String)

/** Reads only the user-selected document; no shared-storage permission required. */
fun readScriptImport(resolver:ContentResolver,uri:Uri):ImportedScript {
                    val filename=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
                        if(cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    require(filename?.endsWith(".js",ignoreCase=true)==true) {"请选择 .js 文件"}
                    val source=resolver.openInputStream(uri)?.use {input->
                        val bytes=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(true) {val count=input.read(buffer);if(count<0) break;require(bytes.size()+count<=4_000_000) {"脚本文件过大"};bytes.write(buffer,0,count)}
                        val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
                        require(text.length<=1_000_000) {"脚本不能超过 100 万字符"}
                        require(!text.contains('\u0000')) {"文件不是有效的 JavaScript 文本"}
                        text
                    } ?: error("无法读取文件")
                    return ImportedScript(filename!!.dropLast(3).trim().ifBlank {"脚本"}.take(40),source)
}
