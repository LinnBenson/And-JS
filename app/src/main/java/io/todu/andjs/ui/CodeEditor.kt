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
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
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
    val line = value.text.take(value.selection.start).count { it=='\n' }+1
    val column = value.selection.start - value.text.lastIndexOf('\n', (value.selection.start-1).coerceAtLeast(-1))
    Column(modifier.background(colors.surface)) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Glyph("code",colors.primary,Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Text("main.js",fontFamily=FontFamily.Monospace,fontSize=12.sp,modifier=Modifier.weight(1f))
            TextButton(onClick={if(undo.isNotEmpty()) {redo.add(value);value=undo.removeAt(undo.lastIndex);onChange(value.text)}},enabled=undo.isNotEmpty(),contentPadding=PaddingValues(horizontal=8.dp)) {Text("撤销",fontSize=12.sp)}
            TextButton(onClick={if(redo.isNotEmpty()) {undo.add(value);value=redo.removeAt(redo.lastIndex);onChange(value.text)}},enabled=redo.isNotEmpty(),contentPadding=PaddingValues(horizontal=8.dp)) {Text("重做",fontSize=12.sp)}
        }
        HorizontalDivider(color=colors.outlineVariant)
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vertical).padding(vertical=12.dp)) {
            Text((1..(value.text.count {it=='\n'}+1)).joinToString("\n"),fontSize=13.sp,lineHeight=22.sp,fontFamily=FontFamily.Monospace,color=colors.onSurfaceVariant.copy(alpha=.6f),modifier=Modifier.widthIn(min=40.dp).padding(horizontal=10.dp))
            Box(Modifier.weight(1f).horizontalScroll(horizontal).padding(end=20.dp)) {
                BasicTextField(value=value,onValueChange={ next ->
                    // Indent only a single newline inserted at a collapsed cursor.
                    val pos=value.selection.start
                    if(value.selection.collapsed && next.text.length==value.text.length+1 && next.selection.start==pos+1 && next.text.getOrNull(pos)=='\n') {
                        val start=value.text.lastIndexOf('\n',pos-1)+1
                        val previous=value.text.substring(start,pos)
                        val indent=previous.takeWhile {it==' ' || it=='\t'} + if(previous.trimEnd().endsWith("{")) "  " else ""
                        update(TextFieldValue(next.text.substring(0,pos+1)+indent+next.text.substring(pos+1),TextRange(pos+1+indent.length)))
                    } else update(next)
                },modifier=Modifier.onFocusChanged {onFocus(it.isFocused)}.widthIn(min=280.dp).fillMaxHeight(),textStyle=TextStyle(color=colors.onSurface,fontSize=13.sp,lineHeight=22.sp,fontFamily=FontFamily.Monospace),cursorBrush=SolidColor(colors.primary),visualTransformation=syntax,keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,capitalization=KeyboardCapitalization.None,keyboardType=KeyboardType.Ascii))
            }
        }
        HorizontalDivider(color=colors.outlineVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
            listOf("⇥","{","}","(",")","[","]",";","'","\"","=",".").forEach { token ->
                TextButton(onClick={insert(if(token=="⇥") "  " else token)},modifier=Modifier.width(42.dp).height(48.dp),contentPadding=PaddingValues(0.dp)) {Text(token,fontSize=16.sp,fontFamily=FontFamily.Monospace)}
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("JavaScript",fontSize=10.sp,color=colors.onSurfaceVariant)
            Text("行 $line · 列 $column",fontSize=10.sp,color=colors.onSurfaceVariant)
        }
    }
}

private class JavascriptHighlight(private val dark: Boolean): VisualTransformation {
    private val tokens=Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`|\\b(?:const|let|var|function|return|if|else|for|while|async|await|export|default|import|from|class|new|throw|try|catch|true|false|null|undefined)\\b|\\b\\d+(?:\\.\\d+)?\\b")
    override fun filter(text: AnnotatedString): TransformedText {
        val result=buildAnnotatedString {
            append(text)
            tokens.findAll(text.text).forEach { match ->
                val token=match.value
                val color=when {
                    token.startsWith("//") || token.startsWith("/*") -> if(dark) Color(0xFF8E9C87) else Color(0xFF788270)
                    token.firstOrNull() in listOf('\'', '"', '`') -> if(dark) Color(0xFFBEDC93) else Color(0xFF426822)
                    token.firstOrNull()?.isDigit()==true -> if(dark) Color(0xFFE6C78D) else Color(0xFF8C6120)
                    else -> if(dark) Color(0xFF9ADAC8) else Color(0xFF187460)
                }
                addStyle(SpanStyle(color=color),match.range.first,match.range.last+1)
            }
        }
        return TransformedText(result,OffsetMapping.Identity)
    }
}
