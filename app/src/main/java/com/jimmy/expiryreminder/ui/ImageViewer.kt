@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimmy.expiryreminder.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
 * 收回终点与来源缩略图同矩形、同 Crop 取景（同一个盒子从 fit 矩形形变过去），
 * 不存在第二张图交叉淡化，落地那一下不会闪。
 * [paths] 为图片相对路径列表（多张时翻页看）。
 */
/**
 * 缩回动画：320ms、起步快末端长缓尾（0.25,0.8,0.25,1）。
 * 240ms 时从整屏缩到 44dp 像「撞」在缩略图上，加长并把速度留给前段，
 * 末段几乎静止后再贴合，观感才连续。展开仍用 280ms，不需要这么慢。
 */
private val CloseSpec = tween<Float>(320, easing = CubicBezierEasing(0.25f, 0.8f, 0.25f, 1f))

/**
 * 归位到 0 后等两帧再撒 Dialog。
 *
 * 少这两帧时，`animateTo(0f)` 刚把值改成 0 就调 `onDismiss()`，Compose 会把
 * 「progress=0」和「移除 Dialog」合并到同一次提交 —— `t=0` 的画面从没被真正绘制过，
 * 屏幕上最后留下的仍是上一帧 `t≈0.02` 的中间态（图比缩略图略大、位置略偏上），
 * 看起来就是「到位后又往上弹了一下才消失」。两帧足以让 `t=0` 先提交并完成绘制。
 */
private suspend fun waitForSettledFrame() {
    withFrameNanos { }
    withFrameNanos { }
}

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
            progress.animateTo(0f, animationSpec = CloseSpec)
            waitForSettledFrame()
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
                    progress.animateTo(0f, animationSpec = CloseSpec)
                }
                launch {
                    // 与盒子归位同一条曲线同时长：两者叠加后的合成轨迹才是单一的、
                    // 不会出现「先缩一下再往回送」的两段感。
                    animate(0f, 1f, animationSpec = CloseSpec) { v, _ ->
                        dragOffset = Offset(startOffset.x * (1f - v), startOffset.y * (1f - v))
                        dragFraction = startFraction * (1f - v)
                    }
                }
            }
            waitForSettledFrame()
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

    // 全屏 Dialog：独立窗口、decorFitsSystemWindows=false 才能盖满整屏（含导航栏区域），
    // 返回键由内容里的 BackHandler 接住走缩回动画（dismissOnBackPress 关掉，避免直接销毁窗口）。
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        // Dialog 默认会给下层窗口压暗（FLAG_DIM_BEHIND），与我们的黑色 scrim 叠加
        // 会让展开/缩回动画期间下层页面变暗，关掉它。
        val dialogView = LocalView.current
        LaunchedEffect(dialogView) {
            (dialogView.parent as? DialogWindowProvider)?.window?.apply {
                // 我们自己的缩放/淡出已经覆盖整个开合过程，系统窗口动画再叠一层
                // 会在结束时把整块内容再推一下，看起来像「弹起」。
                setWindowAnimations(0)
                setDimAmount(0f)
            }
        }
        BackHandler { minimize() }
        var viewSize by remember { mutableStateOf(Size.Zero) }
        val viewW = viewSize.width.coerceAtLeast(1f)
        val viewH = viewSize.height.coerceAtLeast(1f)

        // 顶部控件：随展开淡入，跟手拖拽时按拖拽比例淡出
        val chromeAlpha = ((p / 0.6f).coerceAtMost(1f)) * (1f - dragFraction)
        // 背景压暗：随展开淡入到纯黑（参考酷安），跟手拖拽时同步变透明
        val scrimAlpha = (p * (1f - dragFraction)).coerceIn(0f, 1f)

        Box(
            Modifier
                .fillMaxSize()
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
 * 大图页：转场不缩放整屏画布，而是**插值盒子自身的位置与尺寸**，图片始终填满盒子
 * （`ContentScale.Crop`）。
 * - 盒子 = 缩略图矩形时（progress 0）：Crop 的取景与列表里那张缩略图逐像素一致；
 * - 盒子 = 居中 fit 矩形时（progress 1）：盒子本身就是图片比例，Crop 与 Fit 画的是同一块画面。
 * 于是全程只有一层图、一种裁剪，落地那一下不存在第二张图交叉淡化 → 不再闪。
 * 手势放大/拖动叠加在盒子这层 graphicsLayer 上，跟手关闭的位移/缩小再叠一层。
 */
@Composable
private fun ViewerPage(
    path: String,
    viewport: Size,
    origin: ImageOrigin?,
    progress: Float,
    minimizing: Boolean,
    dismissEnabled: Boolean,
    dismissOffset: Offset,
    dismissFraction: Float,
    onDismissDrag: (totalDelta: Offset) -> Unit,
    onDismissSettle: (commit: Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 手势里要用「视口」而不是视图自身尺寸：转场期间盒子会缩到缩略图大小
    val viewportState = rememberUpdatedState(viewport)
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
    // 默认 TopStart 对齐：下面的 offset 就是相对屏幕左上角的绝对位移（盒子坐标 = 屏幕坐标）
    Box(Modifier.fillMaxSize()) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                strokeWidth = 3.dp,
            )
        } else {
            var scale by remember(bmp) { mutableFloatStateOf(1f) }
            var offset by remember(bmp) { mutableStateOf(Offset.Zero) }

            // 关闭时把手势平滑复位到 1x，保证缩回终点与缩略图完全一致。
            // 用与盒子转场相同的曲线/时长，否则放大后关闭会看到两段速度不同的缩放。
            LaunchedEffect(minimizing) {
                if (minimizing && (scale != 1f || offset != Offset.Zero)) {
                    val fromScale = scale
                    val fromOffset = offset
                    animate(0f, 1f, animationSpec = CloseSpec) { v, _ ->
                        scale = fromScale + (1f - fromScale) * v
                        offset = Offset(fromOffset.x * (1f - v), fromOffset.y * (1f - v))
                    }
                }
            }

            val viewW = viewport.width.coerceAtLeast(1f)
            val viewH = viewport.height.coerceAtLeast(1f)
            val fit = fitSize(bmp.width.toFloat(), bmp.height.toFloat(), viewW, viewH)
            // 转场起止盒子：缩略图矩形 ↔ 屏幕居中的 fit 矩形。
            // 无 origin（比如从通知进入）时退回屏幕中央、18% 视口宽的方块。
            val thumbW = origin?.width?.takeIf { it > 0f } ?: (viewW * 0.18f)
            val thumbH = origin?.height?.takeIf { it > 0f } ?: thumbW
            val thumbX = origin?.x ?: ((viewW - thumbW) / 2f)
            val thumbY = origin?.y ?: ((viewH - thumbH) / 2f)
            val fitX = (viewW - fit.width) / 2f
            val fitY = (viewH - fit.height) / 2f
            val t = progress.coerceIn(0f, 1f)
            val boxW = thumbW + (fit.width - thumbW) * t
            val boxH = thumbH + (fit.height - thumbH) * t
            val boxX = thumbX + (fitX - thumbX) * t
            val boxY = thumbY + (fitY - thumbY) * t
            // 屏幕上的圆角：t=1 收敛为直角，与被点缩略图（8dp）严丝合缝
            val cornerPx = (origin?.cornerRadius ?: 0f) * (1f - t)
            // 与列表缩略图同一套外框（1dp 描边 + 8dp 圆角），随进度淡入淡出。
            // 少了这条描边，图片贴到位、Dialog 撤走的那一帧会多出一圈灰边 ——
            // 那正是「归位后还跳一下」最直接的原因。
            val outlineColor = MaterialTheme.colorScheme.outline
            val outlineAlpha = if (origin != null) (1f - t).coerceIn(0f, 1f) else 0f

            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                    .graphicsLayer {
                        val s = (scale * (1f - 0.12f * dismissFraction)).coerceAtLeast(0.01f)
                        scaleX = s
                        scaleY = s
                        // 位置放在绘制阶段（float、不取整）：原先走布局 offset + roundToInt，
                        // 加上 px→dp→px 往返，归位末段会出现 1px 台阶。
                        translationX = boxX + offset.x + dismissOffset.x
                        translationY = boxY + offset.y + dismissOffset.y
                        // 圆角按屏幕像素给，除以当前缩放换成本地图层像素。clip 常开：
                        // 静止在直角矩形上不切任何内容，也不会因为布尔翻转换渲染层而闪。
                        shape = RoundedCornerShape((cornerPx / s).toDp())
                        clip = true
                    }
                    .drawWithContent {
                        drawContent()
                        if (outlineAlpha > 0.004f) {
                            // 画在图形层内部，会被按 s 缩放，所以宽度/半径都要先除掉 s
                            val s = (scale * (1f - 0.12f * dismissFraction)).coerceAtLeast(0.01f)
                            val stroke = 1.dp.toPx()
                            val r = (cornerPx / s - stroke / 2f).coerceAtLeast(0f)
                            drawRoundRect(
                                color = outlineColor.copy(alpha = outlineAlpha),
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(
                                    (size.width - stroke).coerceAtLeast(0f),
                                    (size.height - stroke).coerceAtLeast(0f),
                                ),
                                cornerRadius = CornerRadius(r),
                                style = Stroke(width = stroke),
                            )
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
                            // 手势判定一律用视口尺寸：盒子转场时会缩到缩略图大小
                            val vp = viewportState.value
                            val vw = vp.width.coerceAtLeast(1f)
                            val vh = vp.height.coerceAtLeast(1f)
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
                                            abs(total.y) > vh * 0.28f ||
                                                abs(total.x) > vw * 0.28f ||
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
                                        vw, vh,
                                        bmp.width.toFloat(), bmp.height.toFloat(),
                                    )
                                    if (scale <= 1f) offset = Offset.Zero
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (wasPinch || scale > 1f) {
                                    val change = pressed[0]
                                    offset = clampOffset(
                                        offset + change.positionChange(), scale,
                                        vw, vh,
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
