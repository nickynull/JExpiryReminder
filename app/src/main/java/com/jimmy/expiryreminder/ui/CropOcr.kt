package com.jimmy.expiryreminder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as UiRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.normalizeImagePath
import com.jimmy.expiryreminder.ocr.DateCandidate
import com.jimmy.expiryreminder.ocr.DateExtractor
import com.jimmy.expiryreminder.ocr.DateRole
import com.jimmy.expiryreminder.ocr.NameCandidate
import com.jimmy.expiryreminder.ocr.NameExtractor
import com.jimmy.expiryreminder.ocr.OcrEngine
import com.jimmy.expiryreminder.ocr.OcrLine
import com.jimmy.expiryreminder.ocr.ShelfLifeCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 圈选识别的一批结果：抽取候选 + 原文行。 */
private data class CropResults(
    val dates: List<DateCandidate> = emptyList(),
    val shelfLives: List<ShelfLifeCandidate> = emptyList(),
    val names: List<NameCandidate> = emptyList(),
    val rawLines: List<String> = emptyList(),
)

/**
 * 圈选识别：在照片上拖一个矩形框，框内区域裁剪放大后重新 OCR。
 *
 * 落盘图最长边只有 1280px，日期喷码在整图里往往不到 200px 高，
 * 全图识别小字经常出错；圈选把目标区域放大到接近引擎舒适区，
 * 等效分辨率翻几倍，是最直接的提准手段。识别结果可以一键填进
 * 对应字段，减少手动输入。
 *
 * [noteOnly] 用于「拍照给备注取字」：允许不拖框直接整图识别，识别行
 * 只提供「加入备注」，不显示日期/品名等字段候选。默认 false 时行为不变。
 */
@Composable
fun CropOcrOverlay(
    paths: List<String>,
    onDismiss: () -> Unit,
    onPickName: (String) -> Unit,
    onPickProduction: (String) -> Unit,
    onPickExpire: (String) -> Unit,
    onPickShelfLife: (Int, ShelfLifeUnit) -> Unit,
    onPickNote: (String) -> Unit,
    noteOnly: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf(0) }
    val safePage = page.coerceIn(0, (paths.size - 1).coerceAtLeast(0))
    val path = paths.getOrNull(safePage) ?: return

    // 当前图、容器尺寸与圈选框（框存原图像素坐标，绘制时换算回屏幕）
    var container by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<CropResults?>(null) }
    var emptyHint by remember { mutableStateOf(false) }
    var fillTarget by remember { mutableStateOf<String?>(null) }

    val loaded = produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(ImageStore.imageFile(context, path).absolutePath)
            }.getOrNull()
        }
    }
    val bmp = loaded.value

    fun clearSelection() {
        dragStart = null
        dragEnd = null
        results = null
        emptyHint = false
    }

    BackHandler { onDismiss() }

    // Popup 是独立 focusable window，返回键走 onDismissRequest；
    // 与 ImageViewer 同款配置，防止返回事件被吞或漏到编辑页
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xEE101314))) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // ---------- 顶栏
                Row(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (noteOnly) "拍照识别文字" else "圈选识别",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (noteOnly) "框住文字放大识别，不框则整图识别"
                            else "框住日期或品名，框内放大重识",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xB3FFFFFF),
                        )
                    }
                    if (paths.size > 1) {
                        Text("${safePage + 1} / ${paths.size}", color = Color(0xB3FFFFFF))
                    }
                }

                // ---------- 图片 + 圈选层
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { container = it },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp == null) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    } else {
                        // ContentScale.Fit 的几何换算；容器尺寸进 key，转场/旋转后不出现过期映射
                        val cw = container.width
                        val ch = container.height
                        val map = fitMapping(cw, ch, bmp.width, bmp.height)
                        fun toBitmap(p: Offset): Offset =
                            Offset((p.x - map.dx) / map.scale, (p.y - map.dy) / map.scale)

                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(bmp, cw, ch) {
                                    detectDragGestures(
                                        onDragStart = { p ->
                                            results = null
                                            emptyHint = false
                                            dragStart = toBitmap(p)
                                            dragEnd = dragStart
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            dragEnd = toBitmap(change.position)
                                        },
                                    )
                                },
                        )

                        // 圈选框：框外压暗 + 白边
                        val start = dragStart
                        val end = dragEnd
                        if (start != null && end != null) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sel = UiRect(
                                    min(start.x, end.x) * map.scale + map.dx,
                                    min(start.y, end.y) * map.scale + map.dy,
                                    max(start.x, end.x) * map.scale + map.dx,
                                    max(start.y, end.y) * map.scale + map.dy,
                                )
                                val hole = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    addRect(UiRect(0f, 0f, size.width, size.height))
                                    addRect(sel)
                                }
                                drawPath(hole, Color(0x99000000))
                                drawRect(
                                    color = Color.White,
                                    topLeft = sel.topLeft,
                                    size = sel.size,
                                    style = Stroke(2.dp.toPx()),
                                )
                            }
                        }
                    }
                }

                // ---------- 缩略图切换
                if (paths.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        paths.forEachIndexed { index, p ->
                            val selected = index == safePage
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .border(
                                        2.dp,
                                        if (selected) Color.White else Color(0x40FFFFFF),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        page = index
                                        clearSelection()
                                    },
                            ) {
                                TinyThumb(path = p, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }

                // ---------- 结果区
                val res = results
                if (res != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        if (noteOnly) {
                            if (res.rawLines.isNotEmpty()) {
                                // 主操作磨砂胶囊：与编辑页保存栏同款（Common.frostedPill）
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .frostedPill(RoundedCornerShape(21.dp))
                                        .clickable {
                                            onPickNote(res.rawLines.joinToString("\n"))
                                            onDismiss()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "全部加入备注",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                ResultSection("识别文字（点一行单独加入）")
                                for (line in res.rawLines) {
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onPickNote(line.trim()) }
                                            .padding(horizontal = 6.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        } else {
                            ResultList(
                                results = res,
                                onPickDate = { c, role ->
                                    if (role == DateRole.produce) onPickProduction(c.isoDate)
                                    else onPickExpire(c.isoDate)
                                },
                                onPickShelf = { onPickShelfLife(it.amount, it.unit) },
                                onPickName = { onPickName(it.text) },
                                onAskFill = { fillTarget = it },
                            )
                        }
                    }
                } else if (emptyHint && !running) {
                    Text(
                        if (noteOnly) "没读出文字，重拍或把框画大再试"
                        else "框内没读出内容，框画大一点再试",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xB3FFFFFF),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                }

                // ---------- 底部按钮
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { clearSelection() },
                        enabled = !running,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("重选") }
                    OutlinedButton(
                        onClick = { clearSelection() },
                        enabled = !running,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = MaterialTheme.shapes.small,
                    ) { Text("重选") }
                    // 主操作磨砂胶囊：落在深色取景底上，白玻璃胶囊与主页同语言；
                    // 白卡内部的行内按钮不能用这套（白上叠白不可见），保留实心 M3 Button
                    Box(
                        Modifier
                            .weight(2f)
                            .height(46.dp)
                            .frostedPill(RoundedCornerShape(23.dp))
                            .alpha(
                                if (!running && (noteOnly || (dragStart != null && dragEnd != null))) {
                                    1f
                                } else {
                                    0.45f
                                },
                            )
                            .clickable {
                                if (running) return@clickable
                                val start = dragStart
                                val end = dragEnd
                                // 有有效框就走区域识别；noteOnly 下无框则整图识别
                                var crop: Rect? = null
                                if (start != null && end != null) {
                                    val left = min(start.x, end.x).roundToInt()
                                    val top = min(start.y, end.y).roundToInt()
                                    val right = max(start.x, end.x).roundToInt()
                                    val bottom = max(start.y, end.y).roundToInt()
                                    if (right - left >= 24 && bottom - top >= 24) {
                                        crop = Rect(left, top, right, bottom)
                                    }
                                }
                                if (!noteOnly && crop == null) return@clickable

                                running = true
                                results = null
                                emptyHint = false
                                scope.launch {
                                    val region = crop
                                    val lines = runCatching {
                                        if (region != null) {
                                            val long = max(region.width(), region.height())
                                            OcrEngine.recognizeRegionFile(
                                                ImageStore.imageFile(context, path),
                                                region,
                                                (long * 2.5f).roundToInt().coerceIn(800, 1536),
                                            )
                                        } else {
                                            OcrEngine.recognizeFile(ImageStore.imageFile(context, path))
                                        }
                                    }.getOrDefault(emptyList())
                                    results = buildResults(lines)
                                    emptyHint = lines.isEmpty()
                                    running = false
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (running) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            val buttonText = if (noteOnly) {
                                val s = dragStart
                                val e = dragEnd
                                val validBox = s != null && e != null &&
                                    max(s.x, e.x) - min(s.x, e.x) >= 24f &&
                                    max(s.y, e.y) - min(s.y, e.y) >= 24f
                                if (validBox) "识别圈选区" else "整图识别"
                            } else {
                                if (dragStart == null) "先拖一个框" else "识 别"
                            }
                            Text(
                                buttonText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    // 点原文行 → 选填到哪个字段
    fillTarget?.let { line ->
        FillLineDialog(
            line = line,
            parsedIso = DateExtractor.extract(listOf(OcrLine(line)), 0).firstOrNull()?.isoDate,
            shelfAmount = Regex("""\d{1,3}""").find(line)?.value?.toIntOrNull(),
            onName = {
                onPickName(line.trim())
                fillTarget = null
            },
            onProduction = { iso ->
                onPickProduction(iso)
                fillTarget = null
            },
            onExpire = { iso ->
                onPickExpire(iso)
                fillTarget = null
            },
            onShelfLife = { amount ->
                onPickShelfLife(amount, ShelfLifeUnit.month)
                fillTarget = null
            },
            onNote = {
                onPickNote(line.trim())
                fillTarget = null
            },
            onDismiss = { fillTarget = null },
        )
    }
}

// ---------------------------------------------------------------- 结果列表

@Composable
private fun ResultList(
    results: CropResults,
    onPickDate: (DateCandidate, DateRole) -> Unit,
    onPickShelf: (ShelfLifeCandidate) -> Unit,
    onPickName: (NameCandidate) -> Unit,
    onAskFill: (String) -> Unit,
) {
    var sectionShown = false

    if (results.dates.isNotEmpty()) {
        sectionShown = true
        ResultSection("日期")
        for (c in results.dates.take(4)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${c.raw}（${roleLabel(c.role)}）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (c.role != DateRole.expire) {
                    TextAction("生产") { onPickDate(c, DateRole.produce) }
                }
                if (c.role != DateRole.produce) {
                    TextAction("到期") { onPickDate(c, DateRole.expire) }
                }
            }
        }
    }

    if (results.shelfLives.isNotEmpty()) {
        sectionShown = true
        ResultSection("保质期")
        for (c in results.shelfLives.take(2)) {
            ResultRow(title = "${c.amount} ${unitLabel(c.unit)}", actionLabel = "填入") {
                onPickShelf(c)
            }
        }
    }

    if (results.names.isNotEmpty()) {
        sectionShown = true
        ResultSection("品名")
        for (c in results.names.take(2)) {
            ResultRow(title = c.text, actionLabel = "填品名") { onPickName(c) }
        }
    }

    if (results.rawLines.isNotEmpty()) {
        sectionShown = true
        ResultSection("框内原文（点一行选填到哪）")
        for (line in results.rawLines.take(20)) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onAskFill(line) }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
        }
    }

    if (!sectionShown) {
        Text(
            "没读出内容，试试把框画大一点",
            color = Color(0xB3FFFFFF),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ResultSection(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(4.dp))
}

/** 一个候选行的展示 + 动作按钮。 */
@Composable
private fun ResultRow(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        TextAction(actionLabel, onAction)
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = Color(0xFF8BD8A0)) }
}

/** 原文行填充菜单：解析不出日期时禁用日期项，防止把批号填进日期字段。 */
@Composable
private fun FillLineDialog(
    line: String,
    parsedIso: String?,
    shelfAmount: Int?,
    onName: () -> Unit,
    onProduction: (String) -> Unit,
    onExpire: (String) -> Unit,
    onShelfLife: (Int) -> Unit,
    onNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("填到哪里？", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    line.take(40),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                DialogOption("作为品名", onName)
                DialogOption("作为生产日期", { onProduction(parsedIso!!) }, enabled = parsedIso != null)
                DialogOption("作为到期日", { onExpire(parsedIso!!) }, enabled = parsedIso != null)
                DialogOption(
                    "作为保质期月数",
                    { onShelfLife(shelfAmount!!) },
                    enabled = shelfAmount != null,
                )
                DialogOption("加入备注", onNote)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DialogOption(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
}

// ---------------------------------------------------------------- 工具

private fun buildResults(lines: List<OcrLine>): CropResults {
    if (lines.isEmpty()) return CropResults()
    val dates = DateExtractor.extract(lines, 0)
    val shelf = DateExtractor.extractShelfLife(lines, 0)
    val names = NameExtractor.extract(lines, 0)
    val raw = lines.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()
    return CropResults(dates, shelf, names, raw)
}

private fun roleLabel(role: DateRole): String = when (role) {
    DateRole.produce -> "生产"
    DateRole.expire -> "有效期至"
    DateRole.unknown -> "日期"
}

private fun unitLabel(unit: ShelfLifeUnit): String = when (unit) {
    ShelfLifeUnit.day -> "天"
    ShelfLifeUnit.month -> "个月"
    ShelfLifeUnit.year -> "年"
}

private class FitMap(val scale: Float, val dx: Float, val dy: Float)

/** ContentScale.Fit 的几何换算：返回缩放比与图在容器里的偏移。 */
private fun fitMapping(cw: Int, ch: Int, bw: Int, bh: Int): FitMap {
    if (cw <= 0 || ch <= 0 || bw <= 0 || bh <= 0) return FitMap(1f, 0f, 0f)
    val scale = min(cw.toFloat() / bw, ch.toFloat() / bh)
    return FitMap(scale, (cw - bw * scale) / 2f, (ch - bh * scale) / 2f)
}

/** 切图用的迷你缩略图，大步长解码不占内存。 */
@Composable
private fun TinyThumb(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = ImageStore.imageFile(context, path)
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = 8 },
                )
            }.getOrNull()
        }
    }
    val thumb = state
    if (thumb != null) {
        Image(
            bitmap = thumb.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}
