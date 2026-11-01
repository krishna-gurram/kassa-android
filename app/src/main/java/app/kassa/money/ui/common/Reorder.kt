package app.kassa.money.ui.common

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.kassa.money.R
import app.kassa.money.ui.theme.Kassa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a reorder list needs to scroll its page while an item is dragged to the top or bottom edge: the page's scroll
 * state and the visible area's top and bottom in window pixels (the owner keeps them current).
 */
@Stable
class AutoScroll(val scroll: ScrollState) {
    var viewTop: Float = 0f
    var viewBottom: Float = 0f
}

/**
 * One draggable column: tracks which item is held, how far it has moved and where it would land. The list lays itself
 * out; each item wears [reorderItem] and one part of it wears [ReorderHandle]. A drop reports the whole new order and
 * the caller stores it; the item settles into its slot first, so nothing jumps. The index maths is [ReorderMath].
 */
@Stable
class ReorderState<K : Any> internal constructor(
    private val gap: Int,
    private val scope: CoroutineScope,
    private val auto: AutoScroll?,
) {
    /** The items in the order they are shown now; the caller sets this on every composition. */
    var keys: List<K> = emptyList()
    internal val heights = HashMap<K, Int>()
    internal val rootTops = HashMap<K, Float>()

    var dragging by mutableStateOf<K?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var target by mutableIntStateOf(-1)
        private set

    private var settling = false
    private var startTop = 0f
    private var startScroll = 0
    private var scrolling: Job? = null

    private fun sizes() = keys.map { heights[it] ?: 0 }

    internal fun shiftOf(key: K): Float {
        val from = dragging?.let { keys.indexOf(it) } ?: return 0f
        return ReorderMath.shift(sizes(), gap, from, target, keys.indexOf(key))
    }

    internal fun start(key: K) {
        if (settling || dragging != null) return
        val i = keys.indexOf(key)
        if (i < 0) return
        startTop = rootTops[key] ?: 0f
        startScroll = auto?.scroll?.value ?: 0
        offset = 0f
        target = i
        dragging = key
        scrolling = auto?.let { a -> scope.launch { followEdges(a) } }
    }

    internal fun drag(dy: Float) {
        if (dragging == null || settling) return
        moveTo(offset + dy)
    }

    private fun moveTo(value: Float) {
        val key = dragging ?: return
        val from = keys.indexOf(key)
        val sizes = sizes()
        offset = ReorderMath.clampOffset(sizes, gap, from, value)
        target = ReorderMath.target(sizes, gap, from, offset)
    }

    /** While held near the top or bottom of the visible area, scrolls the page and keeps the item under the finger. */
    private suspend fun followEdges(a: AutoScroll) {
        while (scope.isActive) {
            androidx.compose.runtime.withFrameNanos { }
            val key = dragging ?: return
            val height = heights[key] ?: 0
            val top = startTop - (a.scroll.value - startScroll) + offset
            val step = ReorderMath.edgeScroll(top, top + height, a.viewTop, a.viewBottom, margin = 72f, maxStep = 24f)
            if (step != 0f) {
                val used = a.scroll.scrollBy(step)
                moveTo(offset + used)
            }
        }
    }

    internal fun end(onMoved: (List<K>) -> Unit) {
        val key = dragging ?: return
        if (settling) return
        scrolling?.cancel()
        settling = true
        val before = keys
        val from = before.indexOf(key)
        val to = target
        scope.launch {
            animate(offset, ReorderMath.snapOffset(sizes(), gap, from, to), animationSpec = tween(120)) { v, _ -> offset = v }
            if (from >= 0 && to >= 0 && to != from) {
                onMoved(ReorderMath.move(before, from, to))
                // Hold the item in place until the list has redrawn in the new order, so it doesn't flash back.
                withTimeoutOrNull(400) { while (keys == before) androidx.compose.runtime.withFrameNanos { } }
            }
            dragging = null
            offset = 0f
            target = -1
            settling = false
        }
    }

    internal fun cancel() {
        if (dragging == null || settling) return
        scrolling?.cancel()
        settling = true
        scope.launch {
            animate(offset, 0f, animationSpec = tween(120)) { v, _ -> offset = v }
            dragging = null
            target = -1
            settling = false
        }
    }
}

/** A [ReorderState] for a column with [gap] between its items. [auto] lets a drag at the edge scroll the page. */
@Composable
fun <K : Any> rememberReorderState(gap: Dp = 0.dp, auto: AutoScroll? = null): ReorderState<K> {
    val px = with(LocalDensity.current) { gap.roundToPx() }
    val scope = rememberCoroutineScope()
    return remember(px, scope, auto) { ReorderState<K>(px, scope, auto) }
}

/**
 * Put on each item of a reorder list. The held item follows the finger and rises on a shadow ([shape] is its outline);
 * the others slide aside to leave its slot open.
 */
@Composable
fun <K : Any> Modifier.reorderItem(state: ReorderState<K>, key: K, shape: Shape = RectangleShape): Modifier {
    val held = state.dragging
    val me = held == key
    val goal = if (held == null || me) 0f else state.shiftOf(key)
    val slide by animateFloatAsState(goal, tween(150), label = "reorderSlide")
    val lift = with(LocalDensity.current) { 12.dp.toPx() }
    return this
        .onSizeChanged { state.heights[key] = it.height }
        .onGloballyPositioned { state.rootTops[key] = it.positionInRoot().y }
        .zIndex(if (me) 1f else 0f)
        .graphicsLayer {
            translationY = when {
                me -> state.offset
                state.dragging == null -> 0f
                else -> slide
            }
            if (me) {
                shadowElevation = lift
                this.shape = shape
                clip = false
            }
        }
}

/**
 * The handle that drags [key]: six dots in a 48dp target. Its description is "Reorder <name>"; for TalkBack it also
 * offers Move up and Move down (each missing at its end of the list), which do what [onUp] and [onDown] do.
 * [onMoved] gets the whole new order when a drag ends.
 */
@Composable
fun <K : Any> ReorderHandle(
    state: ReorderState<K>, key: K, name: String,
    canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit,
    onMoved: (List<K>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val moved by rememberUpdatedState(onMoved)
    val dot = Kassa.colors.ink3
    val reorderText = stringResource(R.string.common_reorder_name, name)
    val moveUpText = stringResource(R.string.common_move_up)
    val moveDownText = stringResource(R.string.common_move_down)
    Box(
        modifier
            .size(48.dp)
            .pointerInput(key, state) {
                detectDragGestures(
                    onDragStart = { state.start(key) },
                    onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
                    onDragEnd = { state.end { moved(it) } },
                    onDragCancel = { state.cancel() },
                )
            }
            .semantics {
                contentDescription = reorderText
                role = Role.Button
                customActions = listOfNotNull(
                    if (canUp) CustomAccessibilityAction(moveUpText) { onUp(); true } else null,
                    if (canDown) CustomAccessibilityAction(moveDownText) { onDown(); true } else null,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val r = 1.6.dp.toPx()
            for (col in 0..1) for (row in 0..2) {
                drawCircle(dot, r, Offset(size.width * (if (col == 0) 0.35f else 0.65f), size.height * (0.25f + 0.25f * row)))
            }
        }
    }
}
