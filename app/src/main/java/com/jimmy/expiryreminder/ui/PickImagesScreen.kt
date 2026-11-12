package com.jimmy.expiryreminder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jimmy.expiryreminder.core.MAX_IMAGES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 选图页：拍照 + 相册混合，最多 [MAX_IMAGES] 张，可逐张删除。
 *
 * 这里**只持 Uri，不落盘**。压缩与写正式目录推迟到「确定」之后（识别进度页），
 * 这样用户中途返回不会留下任何孤儿文件。
 */
@Composable
fun PickImagesScreen(
    onBack: () -> Unit,
    initialUris: List<Uri> = emptyList(),
    onConfirm: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val uris = remember { mutableStateListOf<Uri>().apply { addAll(initialUris) } }
    // 拍照产生的缓存文件，取消时清理；确定后交给进度页处理完再删
    val tempFiles = remember { mutableListOf<File>() }
    var cameraTarget by remember { mutableStateOf<File?>(null) }

    fun goBack() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
        onBack()
    }

    BackHandler { goBack() }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraTarget
        cameraTarget = null
        if (!ok || file == null || !file.exists() || uris.size >= MAX_IMAGES) {
            file?.delete()
            return@rememberLauncherForActivityResult
        }
        tempFiles += file
        runCatching {
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure { file.delete() }
    }

    fun launchCamera() {
        if (uris.size >= MAX_IMAGES) return
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "ocr_${System.currentTimeMillis()}.jpg")
        cameraTarget = file
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            takePicture.launch(uri)
        }.onFailure { cameraTarget = null }
    }

    val pickMultiple = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES),
    ) { picked ->
        val room = MAX_IMAGES - uris.size
        if (room <= 0) return@rememberLauncherForActivityResult
        picked.take(room).forEach { uri -> if (!uris.contains(uri)) uris += uri }
    }

    val full = uris.size >= MAX_IMAGES

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 2.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { goBack() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "选择照片",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "拍包装正面和日期喷码，识别更准",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${uris.size} / $MAX_IMAGES",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (full) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (uris.isEmpty()) {
                EmptyPickHint()
            } else {
                uris.chunked(3).forEachIndexed { rowIndex, rowUris ->
                    if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowUris.forEachIndexed { columnIndex, uri ->
                            val index = rowIndex * 3 + columnIndex
                            PickedThumbnail(
                                uri = uri,
                                onRemove = {
                                    uris.removeAt(index)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowUris.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "照片会作为物品图片一起保存，不用再拍一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { launchCamera() },
                    enabled = !full,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("拍照") }
                OutlinedButton(
                    onClick = {
                        pickMultiple.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !full,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("从相册选择") }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onConfirm(uris.toList()) },
                enabled = uris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("确定（${uris.size}/$MAX_IMAGES）", style = MaterialTheme.typography.titleMedium)
            }
            if (full) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "最多 $MAX_IMAGES 张，删掉一张才能继续添加",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun EmptyPickHint() {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CameraGlyph(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                holeColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "还没有选照片",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "拍照，或从相册挑几张已经拍好的\n品名、配料、日期可以在不同的照片上",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PickedThumbnail(
    uri: Uri,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        UriThumbnail(uri = uri, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "移除这张",
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Ok(val bitmap: Bitmap) : ThumbState
    data object Failed : ThumbState
}

/** 从 Uri 异步加载缩略图，两步解码避免把整张大图读进内存。 */
@Composable
private fun UriThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<ThumbState>(ThumbState.Loading, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext ThumbState.Failed

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 320 || bounds.outHeight / (sample * 2) >= 320) {
                    sample *= 2
                }
                val bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(
                        it, null, BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
                if (bitmap != null) ThumbState.Ok(bitmap) else ThumbState.Failed
            } catch (_: Exception) {
                ThumbState.Failed
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val s = state) {
            is ThumbState.Ok -> Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            ThumbState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
            )
            ThumbState.Failed -> Text(
                "无法预览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
