package io.todu.andjs.ui
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable fun Glyph(name: String, color: Color, modifier: Modifier = Modifier, label: String? = null) {
    val resource=AppIcons.resource(name)
    if(resource!=null) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(resource),contentDescription=label,modifier=modifier,tint=color)
        return
    }
    Canvas(modifier.semantics { if (label != null) contentDescription = label }) {
        val unit = size.minDimension / 24f
        fun line(vararg p: Float) { val path = Path(); path.moveTo(p[0]*unit,p[1]*unit); for(i in 2 until p.size step 2) path.lineTo(p[i]*unit,p[i+1]*unit); drawPath(path,color,style=Stroke(1.7f*unit,cap=StrokeCap.Round)) }
        fun circle(x:Float,y:Float,r:Float) { drawCircle(color,r*unit,Offset(x*unit,y*unit),style=Stroke(1.7f*unit)) }
        when(name) {
            "list" -> {for(y in listOf(6f,12f,18f)) {line(3f,y,4f,y);line(8f,y,21f,y)}}
            "layout-grid-filled" -> {for(x in listOf(3f,14f)) for(y in listOf(3f,14f)) drawRoundRect(color,Offset(x*unit,y*unit),androidx.compose.ui.geometry.Size(7f*unit,7f*unit),androidx.compose.ui.geometry.CornerRadius(unit,unit))}
            "layout-grid" -> {for(x in listOf(3f,14f)) for(y in listOf(3f,14f)) line(x,y,x+7,y,x+7,y+7,x,y+7,x,y)}
            "help" -> {
                circle(12f,12f,9f)
                val p=Path().apply {moveTo(9f*unit,9f*unit);cubicTo(9f*unit,5f*unit,16f*unit,5f*unit,15f*unit,10f*unit);cubicTo(14.5f*unit,12f*unit,12f*unit,11f*unit,12f*unit,14f*unit)}
                drawPath(p,color,style=Stroke(1.7f*unit,cap=StrokeCap.Round))
                drawCircle(color,.9f*unit,Offset(12f*unit,17f*unit))
            }
            "x" -> {line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
            "back" -> line(15f,5f,8f,12f,15f,19f)
            "forward" -> line(9f,5f,16f,12f,9f,19f)
            "check" -> line(4f,12f,9f,17f,20f,6f)
            "folder" -> line(3f,6f,10f,6f,12f,9f,21f,9f,21f,20f,3f,20f,3f,6f)
            "calendar" -> { line(4f,6f,20f,6f,20f,21f,4f,21f,4f,6f); line(4f,11f,20f,11f); line(8f,3f,8f,8f);line(16f,3f,16f,8f) }
            "bolt" -> line(14f,2f,5f,13f,11f,13f,10f,22f,20f,10f,14f,10f,14f,2f)
            "heart" -> { val p=Path().apply {moveTo(12*unit,21*unit);cubicTo(-5*unit,10*unit,4*unit,-2*unit,12*unit,7*unit);cubicTo(20*unit,-2*unit,29*unit,10*unit,12*unit,21*unit)};drawPath(p,color,style=Stroke(1.7f*unit)) }
            "link" -> { line(9f,15f,15f,9f);line(7f,13f,4f,16f,4f,20f,8f,20f,11f,17f);line(13f,7f,16f,4f,20f,4f,20f,8f,17f,11f) }
            "plus" -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
            "search" -> { circle(10f,10f,6f); line(15f,15f,21f,21f) }
            "play" -> line(8f,4f,20f,12f,8f,20f,8f,4f)
            "more" -> listOf(5f,12f,19f).forEach { drawCircle(color,1.4f*unit,Offset(it*unit,12f*unit)) }
            "global" -> { circle(12f,12f,9f); line(3f,12f,21f,12f); line(12f,3f,8f,8f,8f,16f,12f,21f,16f,16f,16f,8f,12f,3f) }
            "history" -> { circle(12f,12f,9f); line(12f,6f,12f,12f,16f,14f) }
            "settings" -> { circle(12f,12f,4f); circle(12f,12f,8f); for(i in 0..7) { val a=i*Math.PI/4; line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+11*kotlin.math.cos(a)).toFloat(),(12+11*kotlin.math.sin(a)).toFloat()) } }
            "note" -> { line(5f,16f,16f,5f,20f,9f,9f,20f,4f,21f,5f,16f); line(14f,7f,18f,11f) }
            "sun" -> { circle(12f,12f,4f); line(12f,1f,12f,4f); line(12f,20f,12f,23f); line(1f,12f,4f,12f); line(20f,12f,23f,12f); line(4f,4f,6f,6f); line(18f,18f,20f,20f); line(4f,20f,6f,18f); line(18f,6f,20f,4f) }
            "text" -> { line(4f,5f,20f,5f); line(4f,12f,17f,12f); line(4f,19f,13f,19f) }
            "swap" -> { line(3f,7f,20f,7f,16f,3f); line(21f,17f,4f,17f,8f,21f) }
            else -> { line(7f,5f,1f,12f,7f,19f); line(17f,5f,23f,12f,17f,19f); line(14f,3f,10f,21f) }
        }
    }
}
