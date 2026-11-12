package com.jimmy.expiryreminder.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.uid
import com.jimmy.expiryreminder.ocr.DateExtractor
import com.jimmy.expiryreminder.ocr.NameCandidate
import com.jimmy.expiryreminder.ocr.NameExtractor
import com.jimmy.expiryreminder.ocr.OcrEngine
import com.jimmy.expiryreminder.ocr.OcrLine
import com.jimmy.expiryreminder.ocr.PrefillDraft
import com.jimmy.expiryreminder.ocr.PrefillMapper
import com.jimmy.expiryreminder.ocr.ResultMerger
import com.jimmy.expiryreminder.ocr.ShelfLifeCandidate
import com.jimmy.expiryreminder.ocr.DateCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 识别进度页：逐张压缩落盘 + OCR，完成后带着预填草稿进添加页。
 *
 * 串行处理而不是并行：ML Kit 的识别器实例并发调用不可靠，且串行能让进度条
 * 真实反映进展。用户随时可以「跳过」，用已识别的部分继续。
 */
@Composable
fun OcrProgressScreen(
    uris: List<Uri>,
    onCancel: () -> Unit,
    onFinish: (PrefillDraft) -> Unit,
) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var skipped by remember { mutableStateOf(false) }
    val total = uris.size

    BackHandler { onCancel() }

    LaunchedEffect(Unit) {
        // 模型初始化放到 IO：拦在主线程上会卡住首帧
        withContext(Dispatchers.IO) { OcrEngine.warmUp() }

        val images = mutableListOf<ExpiryImage>()
        val nameGroups = mutableListOf<List<NameCandidate>>()
        val dateGroups = mutableListOf<List<DateCandidate>>()
        val shelfGroups = mutableListOf<List<ShelfLifeCandidate>>()

        for ((index, uri) in uris.withIndex()) {
            if (skipped) break
            val result = processOne(context, uri)
            if (result == null) {
                failed += 1
            } else {
                val (image, lines) = result
                images += image
                if (lines.isNotEmpty()) {
                    nameGroups += NameExtractor.extract(lines, index)
                    dateGroups += DateExtractor.extract(lines, index)
                    shelfGroups += DateExtractor.extractShelfLife(lines, index)
                }
            }
            done = index + 1
        }

        val merged = ResultMerger.merge(nameGroups, dateGroups, shelfGroups)
        onFinish(PrefillMapper.toDraft(merged, images))
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            CameraGlyph(
                color = MaterialTheme.colorScheme.primary,
                holeColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            if (total > 0) "正在识别 $done/$total" else "正在识别",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        if (failed > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "$failed 张没有读出内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "识别全部在你的手机上完成，照片不会上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { skipped = true }) { Text("跳过识别，直接填写") }
    }
}

/**
 * 处理一张图：压缩落盘 → OCR。
 *
 * 落盘失败返回 null（这张作废）；OCR 失败仍返回图片，只是没有文本——
 * 用户至少能在添加页看到照片，不至于白拍。
 */
private suspend fun processOne(context: Context, uri: Uri): Pair<ExpiryImage, List<OcrLine>>? =
    withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        val id = "img_${uid()}"
        val path = runCatching { ImageStore.saveCompressedImage(context, bytes, id) }.getOrNull()
            ?: return@withContext null

        // 拍照产生的缓存文件不在这里删：用户从本页返回选图页时还要用它做缩略图，
        // 留在 cache/camera 下由系统回收即可。

        val image = ExpiryImage(id = id, path = path, addedAt = Dates.nowIsoTimestamp())
        val lines = runCatching {
            OcrEngine.recognizeFile(ImageStore.imageFile(context, path))
        }.getOrDefault(emptyList())
        image to lines
    }
