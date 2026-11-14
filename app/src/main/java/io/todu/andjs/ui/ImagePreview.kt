package io.todu.andjs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale

@Composable fun ImagePreview(bitmap:ImageBitmap,watch:Boolean=false,onClose:()->Unit) {
    // The decoded log thumbnail is shared with the viewer; no second decode or network request.
    ModalHost(onDismiss=onClose) {
        BackHandler(onBack=onClose)
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
            var scale by remember(bitmap,watch) {mutableFloatStateOf(if(watch) .9f else 1f)}
            var offset by remember(bitmap) {mutableStateOf(Offset.Zero)}
            val density=androidx.compose.ui.platform.LocalDensity.current
            val width=with(density) {maxWidth.toPx()}
            val height=with(density) {maxHeight.toPx()}
            val fit=minOf(width/bitmap.width,height/bitmap.height)
            Box(Modifier.fillMaxSize().pointerInput(onClose) {
                detectTapGestures(onTap={onClose()})
            }.pointerInput(bitmap,width,height) {
                detectTransformGestures {centroid,pan,zoom,_->
                    val next=(scale*zoom).coerceIn(.25f,6f)
                    val relative=centroid-Offset(width/2,height/2)
                    val moved=relative-(relative-offset)*(next/scale)+pan
                    val limitX=((bitmap.width*fit*next-width)/2).coerceAtLeast(0f)
                    val limitY=((bitmap.height*fit*next-height)/2).coerceAtLeast(0f)
                    offset=Offset(moved.x.coerceIn(-limitX,limitX),moved.y.coerceIn(-limitY,limitY))
                    scale=next
                }
            }) {
                Image(bitmap,"图片预览",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().graphicsLayer {
                    scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y
                })
            }

        }
    }
}
