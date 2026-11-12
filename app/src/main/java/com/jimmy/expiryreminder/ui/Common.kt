package com.jimmy.expiryreminder.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jimmy.expiryreminder.core.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 图片加载状态：转圈 → 成功 / 失败（失败显示灰底占位，不再永远转圈）。 */
private sealed interface DataImageLoad {
    data object Loading : DataImageLoad
    data class Ok(val bitmap: android.graphics.Bitmap) : DataImageLoad
    data object Failed : DataImageLoad
}

/** 从数据目录的相对路径异步加载图片（自动降采样，避免大图占内存）。 */
@Composable
fun DataImage(path: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val load by produceState<DataImageLoad>(initialValue = DataImageLoad.Loading, path) {
        value = withContext(Dispatchers.IO) {
            val safe = com.jimmy.expiryreminder.core.normalizeImagePath(path)
            if (safe.isEmpty()) return@withContext DataImageLoad.Failed
            val file = ImageStore.imageFile(context, safe)
            if (!file.exists()) return@withContext DataImageLoad.Failed
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val target = (size.value * 2).toInt().coerceAtLeast(64)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) {
                    sample *= 2
                }
                val bmp = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
                if (bmp != null) DataImageLoad.Ok(bmp) else DataImageLoad.Failed
            } catch (_: Exception) {
                DataImageLoad.Failed
            }
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (val l = load) {
            is DataImageLoad.Ok -> Image(
                bitmap = l.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.size(size),
            )
            is DataImageLoad.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
            )
            DataImageLoad.Failed -> Box(
                Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

/** 简单的异步文件图片（编辑页草稿图片用）。 */
@Composable
fun DraftImage(file: File, size: Dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = 2 },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.size(size),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
fun rememberFileForPath(path: String): File? {
    val context = LocalContext.current
    return remember(path) {
        val safe = com.jimmy.expiryreminder.core.normalizeImagePath(path)
        if (safe.isEmpty()) null else ImageStore.imageFile(context, safe)
    }
}
