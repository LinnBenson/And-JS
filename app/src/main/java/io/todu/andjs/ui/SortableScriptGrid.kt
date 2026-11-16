package io.todu.andjs.ui
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.todu.andjs.domain.Script
import kotlinx.coroutines.launch

@Composable fun SortableScriptGrid(items:List<Script>,onMove:(String,String)->Unit,onDrop:()->Unit,bottomPadding:androidx.compose.ui.unit.Dp=24.dp,content:@Composable (Script)->Unit) {
    val state=rememberLazyGridState()
    var dragging by remember {mutableStateOf<String?>(null)}
    var pointer by remember {mutableStateOf(Offset.Zero)}
    var grab by remember {mutableStateOf(Offset.Zero)}
    val currentMove by rememberUpdatedState(onMove)
    val currentDrop by rememberUpdatedState(onDrop)
    val haptic=LocalHapticFeedback.current
    val scope=rememberCoroutineScope()
    val widthDp=androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width / androidx.compose.ui.platform.LocalDensity.current.density
    val columns=if(widthDp<360) 1 else if(widthDp<600) 2 else 3
    LazyVerticalGrid(columns=GridCells.Fixed(columns),state=state,contentPadding=PaddingValues(top=8.dp,bottom=bottomPadding),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize().pointerInput(state) {
        detectDragGesturesAfterLongPress(onDragStart={point->
            state.layoutInfo.visibleItemsInfo.find {point.x>=it.offset.x && point.x<it.offset.x+it.size.width && point.y>=it.offset.y && point.y<it.offset.y+it.size.height}?.let {
                dragging=it.key as String;pointer=point;grab=point-Offset(it.offset.x.toFloat(),it.offset.y.toFloat());haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        },onDragEnd={dragging=null;currentDrop()},onDragCancel={dragging=null;currentDrop()},onDrag={change,amount->
            if(dragging!=null) {
                change.consume();pointer+=amount
                state.layoutInfo.visibleItemsInfo.find {it.key!=dragging && pointer.x>=it.offset.x && pointer.x<it.offset.x+it.size.width && pointer.y>=it.offset.y && pointer.y<it.offset.y+it.size.height}?.let {currentMove(dragging!!,it.key as String)}
                val edge=64.dp.toPx()
                val delta=when {pointer.y<edge -> -18f;pointer.y>size.height-edge ->18f;else->0f}
                if(delta!=0f) scope.launch {state.scrollBy(delta)}
            }
        })
    }) {
        items(items,key={it.id}) {item->
            val active=dragging==item.id
            val position by remember(state,item.id) {derivedStateOf {if(dragging==item.id) state.layoutInfo.visibleItemsInfo.find {it.key==item.id}?.offset else null}}
            val offset=position?.let {if(active) pointer-grab-Offset(it.x.toFloat(),it.y.toFloat()) else Offset.Zero} ?: Offset.Zero
            val scale by animateFloatAsState(if(active) 1.035f else 1f,label="dragScale")
            val animatedOffset by animateOffsetAsState(offset,animationSpec=if(active) snap() else spring(stiffness=Spring.StiffnessMediumLow),label="dropPosition")
            Box(Modifier.animateItem().zIndex(if(active) 1f else 0f).graphicsLayer {translationX=animatedOffset.x;translationY=animatedOffset.y;scaleX=scale;scaleY=scaleX}) {content(item)}
        }
    }
}
