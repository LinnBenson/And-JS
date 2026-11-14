package io.todu.andjs.ui

/** Measure glyph widths in dp so Latin and Chinese names use the available arc. */
fun watchTileLabel(name:String,sweep:Float,radius:Float):String {
    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=11f}
    val available=(Math.toRadians(sweep.toDouble())*radius-4).toFloat().coerceAtLeast(0f)
    if(paint.measureText(name)<=available) return name
    val ellipsis="…"
    if(paint.measureText(ellipsis)>available) return ""
    var end=paint.breakText(name,true,(available-paint.measureText(ellipsis)).coerceAtLeast(0f),null)
    if(end>0 && end<name.length && Character.isHighSurrogate(name[end-1])) end--
    return name.substring(0,end)+ellipsis
}
