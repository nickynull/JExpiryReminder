@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimmy.expiryreminder.ui

import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.normalizeImagePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 缩略图在屏幕上的位置、尺寸与圆角（px，window 坐标），用于图片查看器做
 * 「从点击的缩略图展开 / 关闭时缩回原处」的共享元素式转场。
 *
 * [cornerRadius] 必须与来源缩略图一致，缩回末段淡入的方形图才能与它严丝合缝。
 */
data class ImageOrigin(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float = 0f,
)

/**
 * 全屏图片查看器：图片按自身比例居中、左右翻页、双指缩放/拖动、双击放大。
 * 打开时从 [origin] 指定的缩略图位置放大（背景淡入到纯黑，参考酷安）；关闭有两条路：
 * 点按/返回键 → 直接缩回该位置；单指拖拽 → 图片跟手位移（横向阻尼）并轻微缩小、
 * 背景同步变透明，松手按位移/速度决定「从当前位置收回到缩略图」或「回弹全屏」。
 * 收回末段交叉淡入一张与缩略图一致的方形图，形状过渡无跳变。
 * [paths] 为图片相对路径列表（多张时翻页看）。
 */
@Composable
fun ImageViewerOverlay(
    paths: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    origin: ImageOrigin? = null,
) {
    var minimizing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 跟手关闭的拖拽状态：位移（横向阻尼后）与比例（0..1，驱动压暗/缩小/背景淡出）
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // 展开进度：0 = 缩略图态，1 = 全屏居中态。
    // 必须在组合期读成普通 Float：直接在图形层 lambda 里读 State 不会稳定重绘。
    val progress = remember { Animatable(0f) }
    val p = progress.value

    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(280, easing = FastOutSlowInEasing))
    }

    fun minimize() {
        if (minimizing) return
        minimizing = true
        scope.launch {
            progress.animateTo(0f, animationSpec = tween(240, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    // 跟手拖拽后的提交：从手指当前位置收回到缩略图（拖拽位移同步归零），
    // 进度动画本身把图片送回 origin 缩略图位置
    fun dismissFromDrag() {
        if (minimizing) return
        minimizing = true
        scope.launch {
            val startOffset = dragOffset
            val startFraction = dragFraction
            coroutineScope {
                launch {
                    progress.animateTo(0f, animationSpec = tween(260, easing = FastOutSlowInEasing))
                }
                launch {
                    animate(0f, 1f, animationSpec = tween(260, easing = FastOutSlowInEasing)) { v, _ ->
                        dragOffset = Offset(startOffset.x * (1f - v), startOffset.y * (1f - v))
                        dragFraction = startFraction * (1f - v)
                    }
                }
            }
            onDismiss()
        }
    }

    // 拖拽幅度/速度不够：回弹到全屏
    fun cancelDismissDrag() {
        scope.launch {
            val startOffset = dragOffset
            val startFraction = dragFraction
            animate(0f, 1f, animationSpec = tween(240, easing = FastOutSlowInEasing)) { v, _ ->
                dragOffset = Offset(startOffset.x * (1f - v), startOffset.y * (1f - v))
                dragFraction = startFraction * (1f - v)
            }
        }
    }

    // 返回键 / 侧滑手势走缩回动画。
    // 关键：Popup 是独立的 focusable window，必须让它自己注册返回回调并映射到 minimize；
    // 若 dismissOnBackPress=false，事件在 Popup window 就被吞掉，Activity 的 BackHandler 收不到，
    // 编辑页还会被 MainActivity 的预测性返回回调抢去直接退出页面。
    BackHandler { minimize() }
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = { minimize() },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            clippingEnabled = false,
        ),
    ) {
        var viewSize by remember { mutableStateOf(Size.Zero) }
        val viewW = viewSize.width.coerceAtLeast(1f)
        val viewH = viewSize.height.coerceAtLeast(1f)

        // 交叉淡化系数：0 = 大图、1 = 方形缩略图。只在缩回末段（p<0.4）切换，
        // 让「长方形大图」在消失前换成「正方形缩略图」，且两张画面内容一致，无感。
        val x = ((0.4f - p) / 0.4f).coerceIn(0f, 1f)
        val thumbAlpha = x * x * (3f - 2f * x) // smoothstep
        val bigAlpha = 1f - thumbAlpha
        // 顶部控件：随展开淡入，跟手拖拽时按拖拽比例淡出
        val chromeAlpha = ((p / 0.6f).coerceAtMost(1f)) * (1f - dragFraction)
        // 背景压暗：随展开淡入到纯黑（参考酷安），跟手拖拽时同步变透明
        val scrimAlpha = (p * (1f - dragFraction)).coerceIn(0f, 1f)

        // 系统按 insets 裁短 Popup 窗口高度（实测 1440x2912，比整屏矮 224px），
        // 底部会漏出下方页面且点击穿透（编辑页会误触保存按钮），强制铺满整屏。
        val density = LocalDensity.current
        val fullHeight = remember(density) {
            with(density) {
                Resources.getSystem().displayMetrics.heightPixels.toFloat().toDp()
            }
        }
        Box(
            Modifier
                .requiredHeight(fullHeight)
                .fillMaxWidth()
                .onGloballyPositioned {
                    viewSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { minimize() })
                },
        ) {
            // 纯黑背景层（最底层）：大图未盖住的区域不再透出下方页面
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            )
            val count = paths.size
            val pagerState = rememberPagerState(
                initialPage = initialIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
                pageCount = { count },
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ViewerPage(
                    path = paths[page],
                    viewport = viewSize,
                    origin = origin,
                    progress = p,
                    minimizing = minimizing,
                    bigAlpha = bigAlpha,
                    dismissEnabled = !minimizing && p >= 1f,
                    dismissOffset = dragOffset,
                    dismissFraction = dragFraction,
                    onDismissDrag = { total ->
                        // 纵向 1:1 跟手，横向给一半阻尼；比例按总位移折算
                        dragOffset = Offset(total.x * 0.5f, total.y)
                        val dist = sqrt(total.x * total.x + total.y * total.y)
                        dragFraction = (dist / (viewH * 0.45f)).coerceIn(0f, 1f)
                    },
                    onDismissSettle = { commit ->
                        if (commit) dismissFromDrag() else cancelDismissDrag()
                    },
                    onTap = { minimize() },
                )
            }
            // 缩回末段淡入的方形缩略图：与来源缩略图同位置、同尺寸、同裁剪、同圆角。
            // 用当前页图片，翻页后关闭也能对上。
            if (origin != null && thumbAlpha > 0f) {
                ThumbOverlay(
                    path = paths[pagerState.currentPage.coerceIn(0, (count - 1).coerceAtLeast(0))],
                    origin = origin,
                    alpha = thumbAlpha,
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .graphicsLayer { alpha = chromeAlpha },
            ) {
                // 深色圆底：下方页面会透出来，白叉直接压在文字上会看不清
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x99000000))
                        .clickable { minimize() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (count > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / $count",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 18.dp)
                            .background(Color(0x66000000), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * 大图页：图片以自身宽高比居中显示，转场用等比缩放 + 平移（不重新测量布局），
 * 因此展开全程保持图片原比例；手势放大/拖动叠加在转场变换之上，
 * 跟手关闭的位移/缩小再叠加在最外层。转场期间按缩略图圆角裁剪，全屏时收敛为直角。
 */
@Composable
private fun ViewerPage(
    path: String,
    viewport: Size,
    origin: ImageOrigin?,
    progress: Float,
    minimizing: Boolean,
    bigAlpha: Float,
    dismissEnabled: Boolean,
    dismissOffset: Offset,
    dismissFraction: Float,
    onDismissDrag: (totalDelta: Offset) -> Unit,
    onDismissSettle: (commit: Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            val safe = normalizeImagePath(path)
            if (safe.isEmpty()) return@withContext null
            val file = ImageStore.imageFile(context, safe)
            if (!file.exists()) return@withContext null
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        } else {
            var scale by remember(bmp) { mutableFloatStateOf(1f) }
            var offset by remember(bmp) { mutableStateOf(Offset.Zero) }

            // 关闭时把手势平滑复位到 1x，保证缩回终点与缩略图完全一致
            LaunchedEffect(minimizing) {
                if (minimizing && (scale != 1f || offset != Offset.Zero)) {
                    val fromScale = scale
                    val fromOffset = offset
                    animate(0f, 1f, animationSpec = tween(180)) { v, _ ->
                        scale = fromScale + (1f - fromScale) * v
                        offset = Offset(fromOffset.x * (1f - v), fromOffset.y * (1f - v))
                    }
                }
            }

            val viewW = viewport.width.coerceAtLeast(1f)
            val viewH = viewport.height.coerceAtLeast(1f)
            val fit = fitSize(bmp.width.toFloat(), bmp.height.toFloat(), viewW, viewH)
            // 起点：把图片等比缩到缩略图宽度、中心对准缩略图中心。
            // 无起点时退回屏幕中央的 0.18 倍。
            val startScale = origin?.let { (it.width / fit.width).coerceIn(0.02f, 1f) } ?: 0.18f
            val startTx = origin?.let { it.x + it.width / 2f - viewW / 2f } ?: 0f
            val startTy = origin?.let { it.y + it.height / 2f - viewH / 2f } ?: 0f
            val t = progress.coerceIn(0f, 1f)
            val remain = 1f - t
            val transScale = startScale + (1f - startScale) * t
            val transTx = startTx * remain
            val transTy = startTy * remain

            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val totalScale = transScale * scale
                        val shrink = 1f - 0.12f * dismissFraction
                        scaleX = totalScale * shrink
                        scaleY = totalScale * shrink
                        translationX = transTx + offset.x + dismissOffset.x
                        translationY = transTy + offset.y + dismissOffset.y
                        alpha = bigAlpha
                        // 屏幕上的圆角随转场进度从缩略图圆角收敛到 0（局部半径按总缩放折算）
                        val screenRadius = (origin?.cornerRadius ?: 0f) * remain
                        if (screenRadius > 1f && totalScale > 0.05f) {
                            shape = RoundedCornerShape(screenRadius / totalScale)
                            clip = true
                        } else {
                            clip = false
                        }
                    }
                    .pointerInput(bmp) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    }
                    .pointerInput(bmp, dismissEnabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                            )
                            var wasPinch = false
                            var dragging = false
                            var total = Offset.Zero
                            val tracker = VelocityTracker()
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    // 手指抬起：跟手关闭按位移/速度决定提交或回弹
                                    if (dragging) {
                                        val v = tracker.calculateVelocity()
                                        onDismissSettle(
                                            abs(total.y) > size.height * 0.28f ||
                                                abs(total.x) > size.width * 0.28f ||
                                                abs(v.y) > 1100f ||
                                                abs(v.x) > 1100f,
                                        )
                                    }
                                    break
                                }
                                if (pressed.size >= 2) {
                                    if (dragging) {
                                        // 拖拽中落下第二根手指：取消关闭，转给缩放手势
                                        dragging = false
                                        onDismissSettle(false)
                                    }
                                    wasPinch = true
                                    val a = pressed[0]
                                    val b = pressed[1]
                                    val prevDist = distance(a.previousPosition, b.previousPosition)
                                    val currDist = distance(a.position, b.position)
                                    val zoom = if (prevDist > 0f) currDist / prevDist else 1f
                                    val panDelta = ((a.positionChange() + b.positionChange())) / 2f
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offset = clampOffset(
                                        offset + panDelta, scale,
                                        size.width.toFloat(), size.height.toFloat(),
                                        bmp.width.toFloat(), bmp.height.toFloat(),
                                    )
                                    if (scale <= 1f) offset = Offset.Zero
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (wasPinch || scale > 1f) {
                                    val change = pressed[0]
                                    offset = clampOffset(
                                        offset + change.positionChange(), scale,
                                        size.width.toFloat(), size.height.toFloat(),
                                        bmp.width.toFloat(), bmp.height.toFloat(),
                                    )
                                    if (scale <= 1f) offset = Offset.Zero
                                    if (change.positionChanged()) change.consume()
                                } else if (dismissEnabled) {
                                    // 未放大时的单指拖拽：纵向越过 slop 即进入跟手关闭
                                    val change = pressed[0]
                                    total += change.positionChange()
                                    if (!dragging) {
                                        val slop = viewConfiguration.touchSlop
                                        if (abs(total.y) > slop && abs(total.y) > abs(total.x)) {
                                            dragging = true
                                        }
                                    }
                                    if (dragging) {
                                        onDismissDrag(total)
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        // 消费事件，避免 HorizontalPager 争抢
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                                event.changes.forEach { if (!it.pressed && it.positionChanged()) it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

/** 缩回末段淡入的方形缩略图，与来源缩略图像素级一致，消除形状跳变。 */
@Composable
private fun ThumbOverlay(path: String, origin: ImageOrigin, alpha: Float) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            val safe = normalizeImagePath(path)
            if (safe.isEmpty()) return@withContext null
            val file = ImageStore.imageFile(context, safe)
            if (!file.exists()) return@withContext null
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Exception) {
                null
            }
        }
    }
    val bmp = bitmap ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(
                width = with(density) { origin.width.toDp() },
                height = with(density) { origin.height.toDp() },
            )
            .offset {
                IntOffset(
                    origin.x.roundToInt(),
                    origin.y.roundToInt(),
                )
            }
            .clip(RoundedCornerShape(with(density) { origin.cornerRadius.toDp() }))
            .graphicsLayer { this.alpha = alpha },
    )
}

/** 图片按 Fit 放进视口后的实际绘制尺寸。 */
private fun fitSize(bmpW: Float, bmpH: Float, viewW: Float, viewH: Float): Size {
    val ratio = bmpW / bmpH
    return if (ratio > viewW / viewH) {
        Size(viewW, viewW / ratio)
    } else {
        Size(viewH * ratio, viewH)
    }
}

/** 把平移量限制在「放大后的图片仍至少盖住视口中心区域」的范围内，避免图被拖出屏幕。 */
private fun clampOffset(
    raw: Offset, scale: Float,
    viewW: Float, viewH: Float,
    bmpW: Float, bmpH: Float,
): Offset {
    val fit = fitSize(bmpW, bmpH, viewW, viewH)
    val maxX = max(0f, (fit.width * scale - viewW) / 2f)
    val maxY = max(0f, (fit.height * scale - viewH) / 2f)
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
