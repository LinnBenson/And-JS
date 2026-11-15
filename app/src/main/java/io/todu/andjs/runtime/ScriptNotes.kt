package io.todu.andjs.runtime

import io.todu.andjs.wear.WatchSnapshot
import org.json.JSONArray

class ScriptNotes(private val read:()->List<String>,private val write:(Int,String)->Unit) {
    fun call(method:String,args:JSONArray):Any {
        require(method=="get" || method=="set") {"未知便签方法"}
        require(args.length()==if(method=="set") 2 else 1) {"便签参数数量不正确"}
        val number=args.get(0)
        require(number is Number && number.toDouble() in 1.0..4.0 && number.toDouble()%1==0.0) {"便签编号须为 1–4 的整数"}
        val index=number.toInt()-1
        if(method=="get") return read()[index]
        val text=args.get(1)
        require(text is String && text.length<=WatchSnapshot.MAX_NOTE_LENGTH) {"便签内容须为字符串，最多 4000 字符"}
        write(index,text)
        return true
    }
}
