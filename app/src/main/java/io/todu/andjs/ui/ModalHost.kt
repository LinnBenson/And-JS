package io.todu.andjs.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalOverlayHost=staticCompositionLocalOf {false}
@Composable fun ModalHost(onDismiss:()->Unit,backEnabled:Boolean=true,content:@Composable ()->Unit) {
    if(LocalOverlayHost.current) {
        Box(Modifier.fillMaxSize().clickable(
            interactionSource=remember {MutableInteractionSource()},indication=null,onClick={}),contentAlignment=Alignment.Center) {content()}
    } else Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false,dismissOnBackPress=backEnabled),content=content)
}
