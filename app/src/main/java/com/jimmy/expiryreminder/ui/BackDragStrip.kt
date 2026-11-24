package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 左边缘跟手返回手势（参考酷安的做法）。
 *
 * 预测性返回已在 Manifest 里彻底关闭（enableOnBackInvokedCallback=false），
 * 系统不会下发 onBackProgressed，也没有可用的系统进度；
 * 酷安的做法是把左边缘申请了系统手势排除后自己处理触摸。这里同样：
 * 铺一条 32dp 左边缘手势区并调用 systemGestureExclusion() 从系统手势里排除
 * （系统对排除矩形有约 200dp 高度配额，超出部分仍走系统返回 → 回落到
 * 原有的按钮同款转场），拖拽进度完全由自己的触摸事件驱动。
 *
 * 结构上拆成两部分，缺一不可：
 * - [GestureExclusionStrip]：只画一个带 systemGestureExclusion 的矩形，
 *   **不带任何 pointerInput**——没有指针输入节点就不会参与命中测试，
 *   左边缘上的正常点击（返回按钮、筛选胶囊等）不会被它挡住。
 * - [Modifier.backDragGesture]：挂在根容器上的拖拽检测，按下点落在
 *   左边缘 [stripWidthDp] 内才激活，未激活不消费任何事件（点击照常下发），
 *   激活（水平位移超过 touchSlop 且大于垂直位移）后消费事件并按
 *   [screenWidthPx] 折算成 0..1 的进度回调 [onDrag]；松手时按位移比例与
 *   横向速度回调 [onFinish](commit)。
 *
 * 之前把拖拽检测直接放在覆盖 Box 上，实测该覆盖层会吞掉下方同级控件
 * （如收件箱返回按钮的左半边）的点击——点击事件不再到达按钮，表现为
 * 「按了没反应」。把手势检测上移到父容器、覆盖层只留手势排除后解决。
 */

/** 只申请系统手势排除、不处理触摸的左边缘矩形：对点击完全透明。 */
@Composable
fun GestureExclusionStrip(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxHeight()
            .width(32.dp)
            .systemGestureExclusion(),
    )
}

/** 左边缘拖拽检测：挂在根容器上，只在按下点位于左边缘 [stripWidthPx] 内时生效。 */
fun Modifier.backDragGesture(
    enabled: Boolean,
    stripWidthPx: Float,
    screenWidthPx: Float,
    onDrag: (progress: Float) -> Unit,
    onFinish: (commit: Boolean) -> Unit,
): Modifier = pointerInput(enabled, stripWidthPx, screenWidthPx) {
    if (!enabled || screenWidthPx <= 0f) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val inStrip = down.position.x <= stripWidthPx
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var active = false
        var totalDx = 0f
        var totalDy = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: event.changes.firstOrNull()
                ?: break
            if (!change.pressed) {
                if (active) {
                    // 消费抬起事件，避免边缘拖拽结束时误触发下方控件的点击
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val velocity = tracker.calculateVelocity()
                    val progress = (totalDx / screenWidthPx).coerceIn(0f, 1f)
                    onFinish(progress > 0.33f || velocity.x > 1000f)
                }
                break
            }
            if (inStrip) {
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                if (!active) {
                    val slop = viewConfiguration.touchSlop
                    if (abs(totalDx) > slop && abs(totalDx) > abs(totalDy)) active = true
                }
                if (active) {
                    onDrag((totalDx / screenWidthPx).coerceIn(0f, 1f))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
            tracker.addPosition(change.uptimeMillis, change.position)
        }
    }
}
