@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimmy.expiryreminder.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.normalizeImagePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 全屏图片查看器：黑底铺满 + 左右翻页 + 双指缩放/拖动 + 双击放大。
 * 点空白区域或按返回键关闭；[paths] 为图片相对路径列表（多张时翻页看）。
 */
@Composable
fun ImageViewerOverlay(
    paths: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 点背景关闭；图片自己消费点击，不会误关
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                },
        ) {
            val count = paths.size
            val pagerState = rememberPagerState(
                initialPage = initialIndex.coerceIn(0, (count - 1).coerceAtLeast(0)),
                pageCount = { count },
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                ViewerPage(path = paths[page])
            }
            // 顶部：关闭按钮 + 页码（statusBarsPadding 防止被状态栏压住）
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "关闭",
                        tint = Color.White,
                    )
                }
                if (count > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / $count",
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
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

/** 单页大图：全量解码（源图已压到 ≤1280，内存可控）+ 手势缩放平移。 */
@Composable
private fun ViewerPage(path: String) {
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

            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    // 双击：1x 与 2.5x 之间切换
                    .pointerInput(bmp) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        })
                    }
                    // 缩放/平移：只有捏合或已放大时才消费手势，
                    // 未放大时单指拖动不消费，交给 Pager 翻页
                    .pointerInput(bmp) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            var wasPinch = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    wasPinch = true
                                    val a = pressed[0]
                                    val b = pressed[1]
                                    val prevDist = distance(a.previousPosition, b.previousPosition)
                                    val currDist = distance(a.position, b.position)
                                    val zoom = if (prevDist > 0f) currDist / prevDist else 1f
                                    val centroid = (a.position + b.position) / 2f
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
                                }
                                event.changes.forEach { if (!it.pressed && it.positionChanged()) it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

/** 把平移量限制在「放大后的图片仍至少盖住视口中心区域」的范围内，避免图被拖出屏幕。 */
private fun clampOffset(
    raw: Offset, scale: Float,
    viewW: Float, viewH: Float,
    bmpW: Float, bmpH: Float,
): Offset {
    // ContentScale.Fit 计算图片铺出来的基准尺寸
    val ratio = bmpW / bmpH
    val fitW: Float
    val fitH: Float
    if (ratio > viewW / viewH) {
        fitW = viewW
        fitH = viewW / ratio
    } else {
        fitH = viewH
        fitW = viewH * ratio
    }
    val maxX = max(0f, (fitW * scale - viewW) / 2f)
    val maxY = max(0f, (fitH * scale - viewH) / 2f)
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
