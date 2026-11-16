package io.todu.andjs.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.luminance
import io.todu.andjs.domain.ApiCatalog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Native editor: matching line gutter, syntax colors, selection-aware insertion and bounded undo. */
@Composable fun CodeEditor(initialText: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,onFocus:(Boolean)->Unit = {}) {
    var value by rememberSaveable(stateSaver=TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initialText)) }
    val undo = remember { mutableStateListOf<TextFieldValue>() }
    val redo = remember { mutableStateListOf<TextFieldValue>() }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val cursorMargin=with(LocalDensity.current) {16.dp.toPx()}
    LaunchedEffect(value.text,value.selection,textLayout,viewportWidth) {
        val layout=textLayout ?: return@LaunchedEffect
        if(viewportWidth<=0 || layout.layoutInput.text.text!=value.text) return@LaunchedEffect
        val cursor=layout.getCursorRect(value.selection.end.coerceIn(0,value.text.length))
        val target=when {
            cursor.left<horizontal.value+cursorMargin -> (cursor.left-cursorMargin).toInt()
            cursor.right>horizontal.value+viewportWidth-cursorMargin -> (cursor.right-viewportWidth+cursorMargin).toInt()
            else -> horizontal.value
        }.coerceIn(0,horizontal.maxValue)
        if(target!=horizontal.value) horizontal.scrollTo(target)
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance()<.5f
    var focused by remember { mutableStateOf(false) }
    val syntax = remember(dark) { JavascriptHighlight(dark) }
    fun update(next: TextFieldValue) {
        if(next.text != value.text) {
            undo.add(value); if(undo.size>80) undo.removeAt(0); redo.clear()
            onChange(next.text)
        }
        value=next
    }
    LaunchedEffect(initialText) {
        if(initialText!=value.text) update(TextFieldValue(initialText))
    }
    fun insert(text: String) {
        val from=value.selection.min; val to=value.selection.max
        update(TextFieldValue(value.text.replaceRange(from,to,text),TextRange(from+text.length)))
    }
    val prefix = remember(value.text,value.selection) {
        if(value.selection.collapsed) Regex("[A-Za-z_$][A-Za-z0-9_$.]*$").find(value.text.take(value.selection.end))?.value.orEmpty() else ""
    }
    val candidates = remember(prefix,value.text) {
        if(prefix.isEmpty()) emptyList() else {
            val api=ApiCatalog.methods.map { EditorSuggestion(it.signature.substringBefore('('),it.signature,it.summary,true) }
            val keywords=listOf("const","let","var","function","return","if","else","for","while","try","catch","throw","new","true","false","null","undefined")
                .map {EditorSuggestion(it,it,"JavaScript",false)}
            val receiver=prefix.substringBeforeLast('.', "")
            val builtins=javascriptSuggestions+if(receiver.isNotEmpty() && receiver!="andjs" && !receiver.startsWith("andjs.")) javascriptMembers.map {
                it.copy(name="$receiver.${it.name}",signature="$receiver.${it.signature}")
            } else emptyList()
            val locals=Regex("\\b(?:const|let|var|function)\\s+([A-Za-z_$][A-Za-z0-9_$]*)").findAll(value.text).map {EditorSuggestion(it.groupValues[1],it.groupValues[1],"当前脚本",false)}.toList()
            (api+builtins+locals+keywords).distinctBy {it.name}.filter {it.name.startsWith(prefix) && it.name!=prefix}.take(8)
        }
    }
    fun complete(candidate:EditorSuggestion) {
        val end=value.selection.end
        val start=end-prefix.length
        val addCall=candidate.call && value.text.getOrNull(end)!='('
        val replacement=candidate.name+if(addCall) "()" else ""
        update(TextFieldValue(value.text.replaceRange(start,end,replacement),TextRange(start+candidate.name.length+if(addCall) 1 else 0)))
    }
    fun symbol(token:String) {
        val close=mapOf("{" to "}","(" to ")","[" to "]","'" to "'","\"" to "\"","`" to "`")[token]
        if(close==null) insert(if(token=="⇥") "   " else token) else {
            val start=value.selection.min;val end=value.selection.max
            val selected=value.text.substring(start,end)
            update(TextFieldValue(value.text.replaceRange(start,end,token+selected+close),TextRange(start+1+selected.length)))
        }
    }
    val callHint=remember(value.text,value.selection) {
        val name=Regex("([A-Za-z_$][A-Za-z0-9_$.]*)\\([^()]*$").find(value.text.take(value.selection.end))?.groupValues?.get(1)
        ApiCatalog.methods.firstOrNull {it.signature.substringBefore('(')==name}?.signature ?: javascriptSuggestions.firstOrNull {it.name==name}?.signature
            ?: javascriptMembers.firstOrNull {it.name==name?.substringAfterLast('.')}?.let {name?.substringBeforeLast('.')+"."+it.signature}
    }
    val line = value.text.take(value.selection.start).count { it=='\n' }+1
    val column = value.selection.start - value.text.lastIndexOf('\n', (value.selection.start-1).coerceAtLeast(-1))
    Column(modifier.background(colors.surface)) {
        if(!focused) {
        Row(Modifier.fillMaxWidth().height(42.dp).background(colors.surfaceContainerLow).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Glyph("code",colors.primary,Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Text("main.js",fontFamily=FontFamily.Monospace,fontSize=12.sp,modifier=Modifier.weight(1f))
            TextButton(onClick={if(undo.isNotEmpty()) {redo.add(value);value=undo.removeAt(undo.lastIndex);onChange(value.text)}},enabled=undo.isNotEmpty(),contentPadding=PaddingValues(horizontal=8.dp)) {Text("撤销",fontSize=12.sp)}
            TextButton(onClick={if(redo.isNotEmpty()) {undo.add(value);value=redo.removeAt(redo.lastIndex);onChange(value.text)}},enabled=redo.isNotEmpty(),contentPadding=PaddingValues(horizontal=8.dp)) {Text("重做",fontSize=12.sp)}
        }
        HorizontalDivider(color=colors.outlineVariant)
        }
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vertical).padding(vertical=12.dp)) {
            Text((1..(value.text.count {it=='\n'}+1)).joinToString("\n"),fontSize=13.sp,lineHeight=22.sp,fontFamily=FontFamily.Monospace,color=colors.onSurfaceVariant.copy(alpha=.6f),modifier=Modifier.widthIn(min=40.dp).padding(horizontal=10.dp))
            Box(Modifier.weight(1f).onSizeChanged {viewportWidth=it.width}.horizontalScroll(horizontal).padding(end=20.dp)) {
                BasicTextField(value=value,onValueChange={ next ->
                    // Indent only a single newline inserted at a collapsed cursor.
                    val pos=value.selection.start
                    if(value.selection.collapsed && next.text.length==value.text.length+1 && next.selection.start==pos+1 && next.text.getOrNull(pos)=='\n') {
                        val start=value.text.lastIndexOf('\n',pos-1)+1
                        val previous=value.text.substring(start,pos)
                        val indent=previous.takeWhile {it==' ' || it=='\t'} + if(previous.trimEnd().endsWith("{") || previous.trimEnd().endsWith("[") || previous.trimEnd().endsWith("(")) "   " else ""
                        val baseIndent=previous.takeWhile {it==' ' || it=='\t'}
                        val closing=mapOf('{' to '}','[' to ']','(' to ')')[previous.trimEnd().lastOrNull()]
                        val tail=if(closing!=null && value.text.getOrNull(pos)==closing) "\n"+baseIndent else ""
                        update(TextFieldValue(next.text.substring(0,pos+1)+indent+tail+next.text.substring(pos+1),TextRange(pos+1+indent.length)))
                    } else if(value.selection.collapsed && next.composition==null && next.text.length==value.text.length+1 && next.selection.start==pos+1) {
                        val typed=next.text[pos]
                        val closing=mapOf('(' to ')','[' to ']','{' to '}','\'' to '\'','"' to '"','`' to '`')[typed]
                        if(typed in ")]}\'\"`" && value.text.getOrNull(pos)==typed) {
                            update(value.copy(selection=TextRange(pos+1)))
                        } else if(closing!=null && (value.text.getOrNull(pos)==null || value.text[pos].isWhitespace() || value.text[pos] in ")]} ,;")) {
                            update(TextFieldValue(next.text.substring(0,pos+1)+closing+next.text.substring(pos+1),TextRange(pos+1)))
                        } else update(next)
                    } else update(next)
                },onTextLayout={textLayout=it},modifier=Modifier.onFocusChanged {focused=it.isFocused;onFocus(it.isFocused)}.widthIn(min=280.dp).fillMaxHeight(),textStyle=TextStyle(color=colors.onSurface,fontSize=13.sp,lineHeight=22.sp,fontFamily=FontFamily.Monospace),cursorBrush=SolidColor(colors.primary),visualTransformation=syntax,keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,capitalization=KeyboardCapitalization.None,keyboardType=KeyboardType.Ascii))
            }
        }
        if(focused && callHint!=null && candidates.isEmpty()) {
            Text(callHint,fontSize=11.sp,fontFamily=FontFamily.Monospace,color=colors.primary,
                modifier=Modifier.fillMaxWidth().background(colors.surfaceContainerLow).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=8.dp))
        }
        if(focused && candidates.isNotEmpty()) {
            HorizontalDivider(color=colors.outlineVariant)
            Column(Modifier.fillMaxWidth().heightIn(max=76.dp).background(colors.surfaceContainerLow).verticalScroll(rememberScrollState())) {
                candidates.forEach {candidate ->
                    Row(Modifier.fillMaxWidth().clickable {complete(candidate)}.height(36.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(candidate.call) "ƒ" else "·",color=colors.primary,fontFamily=FontFamily.Monospace,modifier=Modifier.width(24.dp))
                        Text(candidate.signature,fontSize=12.sp,fontFamily=FontFamily.Monospace,color=colors.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(.62f))
                        Spacer(Modifier.width(12.dp))
                        Text(candidate.description,fontSize=10.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(.38f))
                    }
                }
            }
        }
        HorizontalDivider(color=colors.outlineVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            listOf("⇥","{","}","(",")","[","]",";","'","\"","`","=",".").forEach { token ->
                TextButton(onClick={symbol(token)},modifier=Modifier.width(42.dp).height(48.dp),contentPadding=PaddingValues(0.dp)) {Text(token,fontSize=16.sp,fontFamily=FontFamily.Monospace)}
            }
        }
        if(!focused) Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("JavaScript",fontSize=10.sp,color=colors.onSurfaceVariant)
            Text("行 $line · 列 $column",fontSize=10.sp,color=colors.onSurfaceVariant)
        }
    }
}

private data class EditorSuggestion(val name:String,val signature:String,val description:String,val call:Boolean)

private class JavascriptHighlight(private val dark: Boolean): VisualTransformation {
    private val tokens=Regex("""//[^\n]*|/\*[\s\S]*?(?:\*/|$)|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\b(?:const|let|var|function|return|if|else|for|of|in|while|do|switch|case|break|continue|default|try|catch|finally|throw|new|delete|typeof|instanceof|void|class|extends|super|this|static|get|set|async|await|yield|export|import|from|true|false|null|undefined)\b|\b(?:0[xX][\da-fA-F]+|0[bB][01]+|0[oO][0-7]+|\d+(?:\.\d*)?(?:[eE][+-]?\d+)?n?)\b|\b[A-Za-z_$][\w$]*(?=\s*\()|\b(?:andjs|console|JSON|Math|Object|Array|String|Number|Boolean|Date|RegExp|Error|Map|Set|Infinity|NaN)\b|(?<=\.)[A-Za-z_$][\w$]*|[{}()\[\]]|[+*/%=!<>?:&|~^;-]+""")
    override fun filter(text: AnnotatedString): TransformedText {
        val result=buildAnnotatedString {
            append(text)
            tokens.findAll(text.text).forEach { match ->
                val token=match.value
                val color=when {
                    token.startsWith("//") || token.startsWith("/*") -> if(dark) Color(0xFF939DA5) else Color(0xFF66717A)
                    token.firstOrNull() in listOf('\'', '"', '`') -> if(dark) Color(0xFFB9C9AE) else Color(0xFF506644)
                    token.firstOrNull()?.isDigit()==true -> if(dark) Color(0xFFD5C197) else Color(0xFF79613A)
                    token in listOf("true","false","null","undefined","Infinity","NaN") -> if(dark) Color(0xFFC7B8D1) else Color(0xFF745C82)
                    token in listOf("andjs","console","JSON","Math","Object","Array","String","Number","Boolean","Date","RegExp","Error","Map","Set") -> if(dark) Color(0xFFACC4D4) else Color(0xFF46677C)
                    token !in listOf("if","for","while","switch","catch","function") && token.first().isLetter() && text.text.indexOfFirstAfter(match.range.last+1) == '(' -> if(dark) Color(0xFFD2C6A6) else Color(0xFF78653D)
                    match.range.first>0 && text.text[match.range.first-1]=='.' -> if(dark) Color(0xFFB5C4D3) else Color(0xFF526C83)
                    token.first() in "{}()[]+*/%=!<>?:&|~^;-" -> if(dark) Color(0xFFB9C1CC) else Color(0xFF66717E)
                    else -> if(dark) Color(0xFFAEC9C1) else Color(0xFF446D62)
                }
                addStyle(SpanStyle(color=color),match.range.first,match.range.last+1)
            }
        }
        return TransformedText(result,OffsetMapping.Identity)
    }
}

private fun String.indexOfFirstAfter(start:Int):Char? {
    var index=start
    while(index<length && this[index].isWhitespace()) index++
    return getOrNull(index)
}

private fun suggestions(vararg entries:Pair<String,String>)=entries.map {(signature,description)->
    EditorSuggestion(signature.substringBefore('('),signature,description,signature.contains('('))
}
private val javascriptSuggestions=suggestions(
    "console.log(...values)" to "打印日志",
    "JSON.parse(text)" to "解析 JSON",
    "JSON.stringify(value, replacer, space)" to "生成 JSON 文本",
    "Object.keys(value)" to "所有键名",
    "Object.values(value)" to "所有值",
    "Object.entries(value)" to "键值对数组",
    "Object.assign(target, ...sources)" to "合并对象",
    "Object.fromEntries(entries)" to "键值对转对象",
    "Array.isArray(value)" to "判断数组",
    "Array.from(value, mapFn)" to "创建数组",
    "Array.of(...values)" to "参数转数组",
    "Math.abs(value)" to "绝对值",
    "Math.ceil(value)" to "向上取整",
    "Math.floor(value)" to "向下取整",
    "Math.round(value)" to "四舍五入",
    "Math.trunc(value)" to "截去小数",
    "Math.max(...values)" to "最大值",
    "Math.min(...values)" to "最小值",
    "Math.random()" to "0 到 1 随机数",
    "Math.pow(base, exponent)" to "幂运算",
    "Math.sqrt(value)" to "平方根",
    "Math.PI" to "圆周率",
    "Date.now()" to "当前毫秒时间戳",
    "Date.parse(text)" to "日期转时间戳",
    "Date.UTC(year, month, day)" to "UTC 时间戳",
    "Number.isNaN(value)" to "判断 NaN",
    "Number.isFinite(value)" to "判断有限数",
    "Number.isInteger(value)" to "判断整数",
    "String(value)" to "转换字符串",
    "Number(value)" to "转换数字",
    "Boolean(value)" to "转换布尔值",
    "parseInt(text, radix)" to "解析整数",
    "parseFloat(text)" to "解析小数",
    "isNaN(value)" to "转换后判断 NaN",
    "isFinite(value)" to "转换后判断有限数",
    "encodeURIComponent(text)" to "编码 URL 参数",
    "decodeURIComponent(text)" to "解码 URL 参数",
    "encodeURI(text)" to "编码 URL",
    "decodeURI(text)" to "解码 URL",
    "exit(value)" to "结束并返回结果"
)
private val javascriptMembers=suggestions(
    "length" to "字符串 / 数组长度",
    "toUpperCase()" to "字符串转大写",
    "toLowerCase()" to "字符串转小写",
    "trim()" to "去除首尾空白",
    "trimStart()" to "去除开头空白",
    "trimEnd()" to "去除末尾空白",
    "split(separator, limit)" to "字符串拆分数组",
    "replace(search, replacement)" to "替换匹配内容",
    "match(regexp)" to "正则匹配",
    "search(regexp)" to "正则查找位置",
    "startsWith(text, position)" to "判断开头",
    "endsWith(text, length)" to "判断结尾",
    "includes(value, fromIndex)" to "是否包含",
    "indexOf(value, fromIndex)" to "查找位置",
    "lastIndexOf(value, fromIndex)" to "反向查找位置",
    "slice(start, end)" to "截取字符串 / 数组",
    "substring(start, end)" to "截取字符串",
    "charAt(index)" to "读取字符",
    "charCodeAt(index)" to "字符编码",
    "padStart(length, text)" to "左侧补齐",
    "padEnd(length, text)" to "右侧补齐",
    "repeat(count)" to "重复字符串",
    "join(separator)" to "数组拼接文本",
    "map(callback)" to "映射数组",
    "filter(callback)" to "筛选数组",
    "find(callback)" to "查找元素",
    "findIndex(callback)" to "查找元素位置",
    "forEach(callback)" to "遍历数组",
    "reduce(callback, initialValue)" to "累计计算",
    "some(callback)" to "任意元素满足",
    "every(callback)" to "全部元素满足",
    "push(...values)" to "追加元素",
    "pop()" to "移除末尾元素",
    "shift()" to "移除首个元素",
    "unshift(...values)" to "开头插入元素",
    "splice(start, count, ...values)" to "修改数组",
    "sort(compareFn)" to "原地排序",
    "reverse()" to "原地反转",
    "concat(...values)" to "拼接数组 / 字符串",
    "toFixed(digits)" to "保留小数位",
    "toString(radix)" to "转换文本",
    "getTime()" to "日期毫秒时间戳",
    "getFullYear()" to "读取年份",
    "getMonth()" to "月份 0–11",
    "getDate()" to "月份中的日期",
    "getDay()" to "星期 0–6",
    "getHours()" to "读取小时",
    "getMinutes()" to "读取分钟",
    "getSeconds()" to "读取秒",
    "toISOString()" to "ISO 日期文本",
    "test(text)" to "正则是否匹配",
    "exec(text)" to "正则匹配结果"
)
