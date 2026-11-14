package io.todu.andjs.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.todu.andjs.domain.ScriptCategory

@Composable fun <T> SortableList(
    values: List<T>, keyOf:(T)->String, onMove: (String, String) -> Unit,
    modifier: Modifier = Modifier, onDrop:()->Unit = {}, content: @Composable (T) -> Unit,
) {
    val state = rememberLazyListState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    fun reorder() {
        val id = dragging ?: return
        state.layoutInfo.visibleItemsInfo.find {
            it.key != id && pointer >= it.offset && pointer < it.offset + it.size
        }?.let { move(id, it.key as String) }
    }
    LaunchedEffect(dragging) {
        if (dragging != null) while (true) {
            withFrameNanos { }
            val info = state.layoutInfo
            val delta = when {
                pointer < info.viewportStartOffset + edge -> -10f
                pointer > info.viewportEndOffset - edge -> 10f
                else -> 0f
            }
            if (delta != 0f) { state.scrollBy(delta); reorder() }
        }
    }
    LazyColumn(state=state,modifier=modifier.pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart={point ->
                state.layoutInfo.visibleItemsInfo.find {point.y >= it.offset && point.y < it.offset + it.size}?.let {
                    dragging=it.key as String; pointer=point.y; grab=point.y-it.offset
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDragEnd={dragging=null;drop()}, onDragCancel={dragging=null;drop()},
            onDrag={change,amount -> if(dragging!=null) {change.consume();pointer+=amount.y;reorder()}},
        )
    },verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(top=8.dp,bottom=20.dp)) {
        items(values,key=keyOf) {category ->
            val active=dragging==keyOf(category)
            val position=state.layoutInfo.visibleItemsInfo.find {it.key==keyOf(category)}?.offset ?: 0
            val offset by animateFloatAsState(if(active) pointer-grab-position else 0f,
                animationSpec=if(active) snap() else spring(stiffness=Spring.StiffnessMediumLow),label="categoryDrop")
            val scale by animateFloatAsState(if(active) 1.02f else 1f,label="categoryLift")
            Box(Modifier.animateItem(placementSpec=if(active) null else spring()).zIndex(if(active) 1f else 0f).graphicsLayer {
                translationY=offset;scaleX=scale;scaleY=scale
            }) {content(category)}
        }
    }
}
