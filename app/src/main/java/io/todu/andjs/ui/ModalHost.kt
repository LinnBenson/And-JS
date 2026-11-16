package io.todu.andjs.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

val LocalOverlayHost=staticCompositionLocalOf {false}

// Keep the modal mounted until its exit motion completes, then deliver the action once.
@Composable fun ModalHost(onDismiss:()->Unit,backEnabled:Boolean=true,content:@Composable (close:(()->Unit)->Unit)->Unit) {
    val overlay=LocalOverlayHost.current
    val progress=remember {Animatable(0f)}
    var closing by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val distance=with(LocalDensity.current) {12.dp.toPx()}
    LaunchedEffect(Unit) {
        progress.animateTo(1f,tween(280,easing=CubicBezierEasing(.16f,1f,.3f,1f)))
    }
    val close:(()->Unit)->Unit={action->
        if(!closing) {
            closing=true
            scope.launch {
                progress.animateTo(0f,tween(170,easing=CubicBezierEasing(.4f,0f,1f,1f)))
                action()
            }
        }
    }
    val body:@Composable ()->Unit={
        BackHandler {if(backEnabled && !closing) close {dismiss()}}
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Box(Modifier.matchParentSize().graphicsLayer {alpha=progress.value}
                .background(Color.Black.copy(alpha=.42f)).clickable(
                    interactionSource=remember {MutableInteractionSource()},indication=null,onClick={}))
            Box(Modifier.graphicsLayer {
                val fraction=progress.value
                alpha=(fraction*1.35f).coerceAtMost(1f)
                scaleX=.965f+.035f*fraction
                scaleY=scaleX
                translationY=distance*(1f-fraction)
            },contentAlignment=Alignment.Center) {content(close)}
            if(closing) Box(Modifier.matchParentSize().clickable(
                interactionSource=remember {MutableInteractionSource()},indication=null,onClick={}))
        }
    }
    if(overlay) body() else Dialog(onDismissRequest={if(backEnabled) close {dismiss()}},
        properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false,dismissOnBackPress=false)) {
        val window=(LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setWindowAnimations(0)
        }
        body()
    }
}
