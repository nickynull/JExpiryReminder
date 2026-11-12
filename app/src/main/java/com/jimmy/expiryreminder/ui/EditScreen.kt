@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jimmy.expiryreminder.ui

import android.graphics.DashPathEffect
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.MAX_IMAGES
import com.jimmy.expiryreminder.ocr.DateCandidate
import com.jimmy.expiryreminder.ocr.DateExtractor
import com.jimmy.expiryreminder.ocr.DateRole
import com.jimmy.expiryreminder.ocr.NameCandidate
import com.jimmy.expiryreminder.ocr.NameExtractor
import com.jimmy.expiryreminder.ocr.OcrEngine
import com.jimmy.expiryreminder.ocr.PrefillDraft
import com.jimmy.expiryreminder.ocr.ResultMerger
import com.jimmy.expiryreminder.ocr.ShelfLifeCandidate
import com.jimmy.expiryreminder.core.REMIND_DAY_MAX
import com.jimmy.expiryreminder.core.REMIND_DAY_OPTIONS
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.label
import com.jimmy.expiryreminder.core.normalizeImagePath
import com.jimmy.expiryreminder.core.normalizeRemindDays
import com.jimmy.expiryreminder.core.remindDayLabel
import com.jimmy.expiryreminder.core.uid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val MAX_NOTE = 200

/** 参考稿样式编辑页：单张白色大卡 + 嵌套有效期分组 + 底部通栏保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    itemId: String?,
    defaultCategory: String? = null,
    /** 识别预填结果；纯手动新增时为 null。 */
    prefill: PrefillDraft? = null,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val scope = rememberCoroutineScope()
    val existing = itemId?.let { store.getItem(it) }

    var name by remember { mutableStateOf(existing?.name ?: prefill?.name ?: "") }
    // 新增时优先用主页当前的筛选分类，无筛选（或分类已不存在）则默认「食品」
    var category by remember {
        mutableStateOf(
            existing?.category
                ?: prefill?.category?.takeIf { store.categoryOf(it) != null }
                ?: defaultCategory?.takeIf { store.categoryOf(it) != null }
                ?: Categories.NEW_ITEM_DEFAULT
        )
    }
    var productionDate by remember {
        mutableStateOf(existing?.productionDate ?: prefill?.productionDate ?: "")
    }
    // 归属空间：编辑已有物品用它的空间；新增默认「不归属空间」（空间默认为空）
    var spaceId by remember {
        mutableStateOf(existing?.let { store.resolveSpaceId(it.spaceId) } ?: "")
    }
    var shelfLifeText by remember {
        mutableStateOf(
            existing?.shelfLife?.takeIf { it > 0 }?.toString()
                ?: prefill?.shelfLife?.takeIf { it > 0 }?.toString()
                ?: ""
        )
    }
    var unit by remember {
        mutableStateOf(existing?.shelfLifeUnit ?: prefill?.shelfLifeUnit ?: ShelfLifeUnit.month)
    }
    var expireAt by remember { mutableStateOf(existing?.expireAt ?: prefill?.expireAt ?: "") }
    var manualExpire by remember {
        mutableStateOf(
            if (existing != null) {
                existing.expireAt.isNotEmpty() &&
                    ExpiryCalc.computeExpireAt(
                        existing.productionDate, existing.shelfLife, existing.shelfLifeUnit,
                    ) != existing.expireAt
            } else {
                prefill?.manualExpire ?: false
            }
        )
    }
    var remindDays by remember { mutableStateOf(existing?.remindDays ?: emptyList()) }
    var quantity by remember { mutableStateOf(existing?.quantity ?: 1) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var images by remember { mutableStateOf(existing?.images ?: prefill?.images ?: emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 自定义提前天数的弹窗开关
    var customDayDialog by remember { mutableStateOf(false) }
    // 正在查看的图片下标：null = 查看器未打开
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // 识别徽标：用户在这些字段上产生任何手动输入后，对应徽标就该消失
    var nameFromOcr by remember { mutableStateOf(prefill?.name?.isNotEmpty() == true) }
    var categoryFromOcr by remember { mutableStateOf(prefill?.category != null) }
    var productionFromOcr by remember {
        mutableStateOf(prefill?.productionDate?.isNotEmpty() == true)
    }
    var shelfLifeFromOcr by remember { mutableStateOf((prefill?.shelfLife ?: 0) > 0) }
    var expireFromOcr by remember { mutableStateOf(prefill?.expireAt?.isNotEmpty() == true) }

    // 重新识别会往候选里追加新结果，所以候选列表必须是可变状态（初值来自首次识别）
    var nameCandidates by remember { mutableStateOf(prefill?.nameCandidates.orEmpty()) }
    var dateCandidates by remember { mutableStateOf(prefill?.dateCandidates.orEmpty()) }

    // 未填项/图片读取错误统一走「自动消失」的底部提示，不再占用页面顶部空间
    val snackbar = remember { SnackbarHostState() }
    // 提示分两色：校验失败用错误色，识别完成这类正常反馈用普通色，避免「完成」也显红
    var snackbarIsError by remember { mutableStateOf(true) }
    fun showMessage(msg: String, isError: Boolean) {
        snackbarIsError = isError
        scope.launch {
            // 连续点击保存时不排队，直接用最新提示覆盖
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }
    fun showError(msg: String) = showMessage(msg, isError = true)
    fun showInfo(msg: String) = showMessage(msg, isError = false)
    val scrollState = rememberScrollState()

    // 新增时自动聚焦名称输入框并弹出键盘，省掉一次点击。
    // 识别预填进来时字段已有值，此时弹键盘会直接盖住识别结果与候选区，所以跳过。
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (existing == null && prefill == null) {
            // 等首帧布局完成，否则输入框还没挂上，requestFocus 可能无效
            withFrameNanos { }
            nameFocus.requestFocus()
        }
    }

    // 自动推算到期日
    LaunchedEffect(productionDate, shelfLifeText, unit, manualExpire) {
        if (!manualExpire) {
            val amount = shelfLifeText.toIntOrNull() ?: 0
            expireAt = ExpiryCalc.computeExpireAt(productionDate, amount, unit)
        }
    }

    // 拍照：先落到缓存文件，回调里压缩入库
    var cameraTarget by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraTarget
        if (ok && file != null && file.exists()) {
            scope.launch(Dispatchers.IO) {
                val bytes = file.readBytes()
                val path = ImageStore.saveCompressedImage(context, bytes, "img_${uid()}")
                if (path != null && images.size < MAX_IMAGES) {
                    images = images + ExpiryImage(id = "img_${uid()}", path = path, addedAt = Dates.nowIsoTimestamp())
                }
                file.delete()
            }
        }
        cameraTarget = null
    }
    fun launchCamera() {
        if (images.size >= MAX_IMAGES) return
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "take_${System.currentTimeMillis()}.jpg")
        cameraTarget = file
        val authority = "${context.packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        takePicture.launch(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && images.size < MAX_IMAGES) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val path = ImageStore.saveCompressedImage(context, bytes, "img_${uid()}")
                        if (path != null) {
                            images = images + ExpiryImage(id = "img_${uid()}", path = path, addedAt = Dates.nowIsoTimestamp())
                        } else {
                            showError("无法读取所选图片")
                        }
                    }
                } catch (_: Exception) {
                    showError("读取图片失败")
                }
            }
        }
    }

    // 日期选择器
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerTarget by remember { mutableStateOf("expire") } // expire | production
    val datePickerState = rememberDatePickerState()
    // 图片添加来源菜单
    var imageSourceMenu by remember { mutableStateOf(false) }
    // 分类/单位下拉
    var categoryMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var spaceMenu by remember { mutableStateOf(false) }

    // 图片：拖动排序 / 重新识别（都由按钮触发）
    var sortDialog by remember { mutableStateOf(false) }
    var reOcrDialog by remember { mutableStateOf(false) }
    var reOcrRunning by remember { mutableStateOf(false) }
    var reOcrDone by remember { mutableStateOf(0) }
    var reOcrTotal by remember { mutableStateOf(0) }

    /**
     * 重新识别选中的图片。
     *
     * 只做「补充」：候选去重后追加，字段只在空着时填；用户已经填/改过的内容一律不动。
     * 串行识别——ML Kit 的识别器实例并发调用不可靠。
     */
    fun reRecognize(targets: List<ExpiryImage>) {
        if (targets.isEmpty() || reOcrRunning) return
        reOcrRunning = true
        reOcrDone = 0
        reOcrTotal = targets.size
        reOcrDialog = false
        scope.launch {
            val nameGroups = mutableListOf<List<NameCandidate>>()
            val dateGroups = mutableListOf<List<DateCandidate>>()
            val shelfGroups = mutableListOf<List<ShelfLifeCandidate>>()
            var emptyCount = 0
            for ((index, image) in targets.withIndex()) {
                val safePath = normalizeImagePath(image.path)
                val lines = if (safePath.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { OcrEngine.recognizeFile(ImageStore.imageFile(context, safePath)) }
                            .getOrDefault(emptyList())
                    }
                }
                if (lines.isEmpty()) {
                    emptyCount += 1
                } else {
                    nameGroups += NameExtractor.extract(lines, index)
                    dateGroups += DateExtractor.extract(lines, index)
                    shelfGroups += DateExtractor.extractShelfLife(lines, index)
                }
                reOcrDone = index + 1
            }
            val merged = ResultMerger.merge(nameGroups, dateGroups, shelfGroups)

            // 候选：按值去重后并进现有列表，交给用户点选
            val knownDates = dateCandidates.map { it.isoDate }.toSet()
            dateCandidates = dateCandidates + merged.dates.filter { it.isoDate !in knownDates }
            val knownNames = nameCandidates.map { it.text }.toSet()
            nameCandidates = nameCandidates + merged.names.filter { it.text !in knownNames }

            // 字段：只填空着的
            val bestName = merged.names.firstOrNull()
            if (name.isBlank() && bestName != null) {
                name = bestName.text
                nameFromOcr = true
            }
            val life = merged.shelfLives.filter { it.prefillable }.maxByOrNull { it.score }
            if (shelfLifeText.isEmpty() && life != null) {
                shelfLifeText = life.amount.toString()
                unit = life.unit
                shelfLifeFromOcr = true
            }
            val produce = merged.dates
                .filter { it.role == DateRole.produce && it.prefillable }
                .maxByOrNull { it.score }
            if (productionDate.isEmpty() && produce != null) {
                productionDate = produce.isoDate
                productionFromOcr = true
            }
            val expire = merged.dates
                .filter { it.role == DateRole.expire && it.prefillable }
                .maxByOrNull { it.score }
            if (expireAt.isEmpty() && expire != null) {
                expireAt = expire.isoDate
                expireFromOcr = true
                manualExpire = true
            }

            reOcrRunning = false
            if (merged.dates.isEmpty() && merged.names.isEmpty()) {
                showError(
                    if (emptyCount == targets.size) "${targets.size} 张都没读出内容"
                    else "没有识别出新内容",
                )
            } else {
                showInfo("重新识别完成，新结果已补进候选")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // 紧凑头部（替代 TopAppBar 的 64dp，压到 48dp）
            // 头部与页面底色一致，状态栏区域无缝衔接
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDone, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (existing == null) "新增物品" else "编辑物品",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "食品、药品、证件的有效期管理",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // ============================================== 表单大卡（白底页面靠描边区分）
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // -------------------------------------- 物品名称
                        FieldLabel("物品名称", ocrBadge = nameFromOcr)
                        InputBox(
                            value = name,
                            onValueChange = { name = it; nameFromOcr = false },
                            placeholder = "如：维生素 C、护照、牛奶",
                            fixedHeight = 48.dp,
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = nameFocus,
                            showClear = true,
                        )

                        // 品名候选：文字长，用竖排单选而不是横排 chip
                        if (nameCandidates.size >= 2) {
                            NameCandidateList(
                                candidates = nameCandidates,
                                selectedText = name,
                                onSelect = { picked ->
                                    name = picked
                                    nameFromOcr = false
                                },
                            )
                        }

                        // -------------------------------------- 归属空间
                        FieldLabel("空间")
                        Box {
                            SelectBox(
                                text = store.spaceLabelOf(spaceId),
                                leading = {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .background(hexColor(store.spaceColorOf(spaceId)), CircleShape),
                                    )
                                },
                                onClick = { spaceMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(
                                expanded = spaceMenu,
                                onDismissRequest = { spaceMenu = false },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                // 空间可以为空：不选任何空间时归入「未分配」
                                DropdownMenuItem(
                                    text = { Text("不归属空间") },
                                    leadingIcon = {
                                        Box(
                                            Modifier.size(10.dp).background(
                                                hexColor(com.jimmy.expiryreminder.core.Spaces.UNASSIGNED_COLOR),
                                                CircleShape,
                                            )
                                        )
                                    },
                                    onClick = {
                                        spaceId = ""
                                        spaceMenu = false
                                    },
                                )
                                for (space in store.spaceOptions()) {
                                    DropdownMenuItem(
                                        text = { Text(space.label) },
                                        leadingIcon = {
                                            Box(
                                                Modifier.size(10.dp).background(
                                                    hexColor(space.color), CircleShape,
                                                )
                                            )
                                        },
                                        onClick = {
                                            spaceId = space.id
                                            spaceMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        // -------------------------------------- 分类 + 数量
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1.4f)) {
                                FieldLabel("分类", ocrBadge = categoryFromOcr)
                                Box {
                                    SelectBox(
                                        text = store.categoryLabelOf(category),
                                        leading = {
                                            Box(
                                                Modifier
                                                    .size(10.dp)
                                                    .background(
                                                        hexColor(store.categoryColorOf(category)),
                                                        CircleShape,
                                                    ),
                                            )
                                        },
                                        onClick = { categoryMenu = true },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    DropdownMenu(
                                        expanded = categoryMenu,
                                        onDismissRequest = { categoryMenu = false },
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        for (cat in store.categoryOptions()) {
                                            DropdownMenuItem(
                                                text = { Text(cat.label) },
                                                leadingIcon = {
                                                    Box(
                                                        Modifier.size(10.dp).background(
                                                            hexColor(cat.color), CircleShape,
                                                        )
                                                    )
                                                },
                                                onClick = {
                                                    category = cat.id
                                                    categoryMenu = false
                                                    categoryFromOcr = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                FieldLabel("数量")
                                QuantityStepper(
                                    value = quantity,
                                    onChange = { quantity = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        // -------------------------------------- 识别出的日期候选
                        // 显示条件：存在「没被填进任何字段」的候选。只判断 size >= 2 会留死角——
                        // 唯一候选分数不足 0.7 时既不预填也不显示，识别了等于白识别
                        val dateCandidatesVisible = dateCandidates.any {
                            it.isoDate != expireAt && it.isoDate != productionDate
                        }
                        if (dateCandidatesVisible) {
                            // 角色判不出来的日期不再替用户猜成「到期」，点开由用户选填哪个字段
                            DateCandidateList(
                                candidates = dateCandidates,
                                productionDate = productionDate,
                                expireAt = expireAt,
                                onFill = { candidate, asProductionDate ->
                                    if (asProductionDate) {
                                        productionDate = candidate.isoDate
                                        // 有保质期就回到自动推算；没有则保留已填到期日，避免被清空
                                        manualExpire = expireAt.isNotEmpty() &&
                                            (shelfLifeText.toIntOrNull() ?: 0) <= 0
                                    } else {
                                        expireAt = candidate.isoDate
                                        manualExpire = true
                                    }
                                    productionFromOcr = false
                                    expireFromOcr = false
                                },
                            )
                        }

                        // -------------------------------------- 有效期（嵌套灰底分组）
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "有效期",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1.6f)) {
                                    FieldLabel("生产日期", optional = true, ocrBadge = productionFromOcr)
                                    SelectBox(
                                        text = productionDate.ifEmpty { "选择日期" },
                                        onClick = {
                                            datePickerTarget = "production"; showDatePicker = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Column(Modifier.weight(1.2f)) {
                                    FieldLabel("有效期", optional = true, ocrBadge = shelfLifeFromOcr)
                                    InputBox(
                                        value = shelfLifeText,
                                        onValueChange = {
                                            shelfLifeText = it.filter { c -> c.isDigit() }.take(5)
                                            shelfLifeFromOcr = false
                                        },
                                        placeholder = "选填",
                                        fixedHeight = 48.dp,
                                        modifier = Modifier.fillMaxWidth(),
                                        showClear = true,
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    FieldLabel("单位")
                                    Box {
                                        SelectBox(
                                            text = unit.label,
                                            trailing = {
                                                Icon(
                                                    Icons.Filled.KeyboardArrowDown, contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            onClick = { unitMenu = true },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        DropdownMenu(
                                            expanded = unitMenu,
                                            onDismissRequest = { unitMenu = false },
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            for (u in ShelfLifeUnit.entries) {
                                                DropdownMenuItem(
                                                    text = { Text(u.label) },
                                                    onClick = { unit = u; unitMenu = false },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            FieldLabel("到期日期", ocrBadge = expireFromOcr)
                            SelectBox(
                                text = expireAt.ifEmpty { "选择日期" },
                                leading = {
                                    Icon(
                                        Icons.Filled.DateRange, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                onClick = { datePickerTarget = "expire"; showDatePicker = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        expireAt.isEmpty() -> "由生产日期与有效期推算，也可手动指定"
                                        else -> Dates.describeRemaining(expireAt)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (manualExpire && productionDate.isNotEmpty() &&
                                    (shelfLifeText.toIntOrNull() ?: 0) > 0
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "恢复自动推算",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            manualExpire = false
                                            val amount = shelfLifeText.toIntOrNull() ?: 0
                                            expireAt =
                                                ExpiryCalc.computeExpireAt(productionDate, amount, unit)
                                        },
                                    )
                                }
                            }
                        }

                        // -------------------------------------- 提前提醒
                        FieldLabel("提前提醒")
                        // 预设档位之外的用户自填天数：与预设一起按由远到近排，「当天」固定收尾
                        val customRemindDays =
                            remindDays.filter { it !in REMIND_DAY_OPTIONS }.sortedDescending()
                        val remindChips =
                            (REMIND_DAY_OPTIONS.filter { it > 0 } + customRemindDays)
                                .sortedDescending() + REMIND_DAY_OPTIONS.filter { it == 0 }
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (day in remindChips) {
                                val selectedDay = day in remindDays
                                FilterChip(
                                    selected = selectedDay,
                                    onClick = {
                                        remindDays =
                                            if (day in remindDays) remindDays - day else remindDays + day
                                    },
                                    label = { Text(remindDayLabel(day)) },
                                    modifier = Modifier.height(30.dp),
                                    trailingIcon = if (selectedDay) {
                                        {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "移除",
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    } else null,
                                )
                            }
                            // 自定义入口：录入 1~365 天，确认后作为一枚档位加入
                            FilterChip(
                                selected = false,
                                onClick = { customDayDialog = true },
                                label = { Text("自定义") },
                                modifier = Modifier.height(30.dp),
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                },
                            )
                        }
                        if (remindDays.isEmpty()) {
                            Text(
                                "未选择则不推送到期提醒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // -------------------------------------- 备注
                        FieldLabel("备注", optional = true)
                        InputBox(
                            value = note,
                            onValueChange = { note = it.take(MAX_NOTE) },
                            placeholder = "存放位置、开封日期等",
                            modifier = Modifier.fillMaxWidth(),
                            showClear = true,
                        )
                        Text(
                            "${note.length} / $MAX_NOTE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // -------------------------------------- 图片
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FieldLabel("图片", optional = true)
                            Spacer(Modifier.weight(1f))
                            if (images.size >= 2) {
                                Text(
                                    "排序",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { sortDialog = true }
                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            if (images.isNotEmpty()) {
                                Text(
                                    if (reOcrRunning) "识别中 $reOcrDone/$reOcrTotal" else "重新识别",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (reOcrRunning) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = !reOcrRunning) { reOcrDialog = true }
                                        .padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                "${images.size} / $MAX_IMAGES",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 虚线添加框
                            Box(
                                Modifier
                                    .size(76.dp)
                                    .dashedBorder(
                                        color = MaterialTheme.colorScheme.outline,
                                        cornerRadius = 12.dp,
                                    )
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = images.size < MAX_IMAGES) { imageSourceMenu = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "添加",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = imageSourceMenu,
                                onDismissRequest = { imageSourceMenu = false },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("拍照") },
                                    onClick = { imageSourceMenu = false; launchCamera() },
                                )
                                DropdownMenuItem(
                                    text = { Text("从相册选择") },
                                    onClick = { imageSourceMenu = false; pickImage.launch("image/*") },
                                )
                            }
                            // 缩略图（点击查看大图，右上角小圆点是删除）
                            images.forEachIndexed { imgIndex, img ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { viewerIndex = imgIndex },
                                ) {
                                    val file = rememberFileForPath(img.path)
                                    if (file != null) {
                                        DraftImage(
                                            file = file, size = 76.dp,
                                            modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                                        )
                                    } else {
                                        Box(
                                            Modifier.size(76.dp).clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color(0x99000000))
                                            .clickable { images = images - img },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Close, contentDescription = "删除图片",
                                            tint = Color.White, modifier = Modifier.size(13.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "提醒依赖应用运行时的检查；关闭到期通知后将不再推送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
            }

            // ---------------------------------------------- 底部固定保存栏
            Button(
                onClick = {
                    val finalName = name.trim()
                    if (finalName.isEmpty()) {
                        showError("请填写物品名称")
                        // 提示在底部，同时把视线滚回顶部，避免用户看不到要填的字段
                        scope.launch { scrollState.animateScrollTo(0) }
                        return@Button
                    }
                    if (expireAt.isEmpty()) {
                        showError("请填写到期日期（生产日期 + 有效期，或手动指定）")
                        scope.launch { scrollState.animateScrollTo(0) }
                        return@Button
                    }
                    if (!Dates.isValidISODate(expireAt)) {
                        showError("到期日期格式不正确")
                        return@Button
                    }
                    val amount = shelfLifeText.toIntOrNull() ?: 0
                    val draft = ExpiryItem(
                        id = existing?.id ?: "",
                        name = finalName,
                        category = category,
                        productionDate = productionDate,
                        shelfLife = amount,
                        shelfLifeUnit = unit,
                        expireAt = expireAt,
                        remindDays = remindDays,
                        quantity = quantity,
                        note = note.trim(),
                        images = images,
                        notifiedKeys = existing?.notifiedKeys ?: emptyList(),
                        archived = existing?.archived ?: false,
                        createdAt = existing?.createdAt ?: Dates.nowIsoTimestamp(),
                        updatedAt = Dates.nowIsoTimestamp(),
                        spaceId = store.resolveSpaceId(spaceId),
                    )
                    scope.launch {
                        if (existing == null) store.createItem(context, draft)
                        else store.updateItem(context, existing.id, draft)
                        onDone()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text(if (existing == null) "保存" else "保存", style = MaterialTheme.typography.titleMedium) }
        }

        // 底部浮层提示：贴在保存栏上方，几秒后自动消失，不占页面高度
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .padding(bottom = 74.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(10.dp),
                containerColor = if (snackbarIsError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (snackbarIsError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
                dismissActionContentColor = if (snackbarIsError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                        if (datePickerTarget == "production") {
                            productionDate = date.toString()
                            productionFromOcr = false
                        } else {
                            expireAt = date.toString()
                            manualExpire = true
                            expireFromOcr = false
                        }
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除物品") },
            text = { Text("确定删除「${existing.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        store.removeItem(context, existing.id)
                        onDone()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    // 自定义提前天数：确认后并入档位（越界/重复在弹窗内拦下）
    if (customDayDialog) {
        CustomRemindDayDialog(
            remindDays = remindDays,
            onConfirm = { day ->
                remindDays = normalizeRemindDays(remindDays + day)
                customDayDialog = false
            },
            onDismiss = { customDayDialog = false },
        )
    }

    // 图片拖动排序：只在「排序」按钮触发的弹窗里生效，日常点击缩略图仍是看大图
    if (sortDialog && images.size >= 2) {
        ImageSortDialog(
            images = images,
            onMove = { from, to ->
                if (from != to && from in images.indices && to in images.indices) {
                    images = images.toMutableList().apply { add(to, removeAt(from)) }
                }
            },
            onDismiss = { sortDialog = false },
        )
    }

    // 重新识别：先选对象，再执行
    if (reOcrDialog && images.isNotEmpty()) {
        ReRecognizeDialog(
            images = images,
            onConfirm = { targets -> reRecognize(targets) },
            onDismiss = { reOcrDialog = false },
        )
    }

    // 全屏图片查看器：编辑中的草稿图片也能看（文件在入库时就已落盘）
    viewerIndex?.let { idx ->
        ImageViewerOverlay(
            paths = images.map { it.path },
            initialIndex = idx,
            onDismiss = { viewerIndex = null },
        )
    }
}

// ---------------------------------------------------------------- 私有小组件

/** 字段标签：黑字 + 可选的「选填」灰字。 */
@Composable
private fun FieldLabel(text: String, optional: Boolean = false, ocrBadge: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        // 已由识别填入时不再标「选填」——两个标签挤在一行会看不清
        if (optional && !ocrBadge) {
            Spacer(Modifier.width(6.dp))
            Text(
                "选填",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ocrBadge) {
            Spacer(Modifier.width(6.dp))
            Text(
                "识别",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 自定义提前天数弹窗：只收 1~[REMIND_DAY_MAX] 的正整数，越界与重复档位在弹窗内就地拦下，
 * 免得加进档位后才发现「填了也不生效」。
 */
@Composable
private fun CustomRemindDayDialog(
    remindDays: List<Int>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 等输入框挂到树上再收焦点，否则键盘不弹
        withFrameNanos { }
        focus.requestFocus()
    }

    fun submit() {
        val day = text.toIntOrNull()
        val message = when {
            text.isEmpty() -> "请输入天数"
            day == null -> "请输入数字"
            else -> when {
                day < 1 -> "天数需大于 0"
                day > REMIND_DAY_MAX -> "最多提前 $REMIND_DAY_MAX 天"
                day in remindDays -> "已添加该档位"
                else -> null
            }
        }
        error = message
        if (message == null && day != null) onConfirm(day)
    }

    val errorText = error
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义提前天数") },
        text = {
            Column {
                Text(
                    "到期前第几天提醒，可填 1~$REMIND_DAY_MAX 天。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                InputBox(
                    value = text,
                    onValueChange = {
                        text = it.filter { c -> c.isDigit() }.take(3)
                        error = null
                    },
                    placeholder = "例如 45",
                    fixedHeight = 48.dp,
                    focusRequester = focus,
                    isError = errorText != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 品名候选：竖排单选。
 *
 * 品名动辄 5–10 个字，用日期的横排 chip 会挤成两行还断在词中间，
 * 所以这里改成整行可点。
 */
@Composable
private fun NameCandidateList(
    candidates: List<NameCandidate>,
    selectedText: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "检出 ${candidates.size} 个名称，选一个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (candidate in candidates) {
            val selected = candidate.text == selectedText
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(candidate.text) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        candidate.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (candidate.imageCount >= 2) {
                        Text(
                            "多图一致",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 日期候选：横排 chip。
 *
 * 一张包装常同时印着生产日期、批号、有效期，让用户点一下比让程序猜更可靠。
 * 关键词能定角色的候选点一下直接填对应字段；判不出角色的（[DateRole.unknown]）
 * 点开菜单让用户自己决定填到生产日期还是到期日期。
 * 已被采用的候选会高亮并标出填到了哪个字段。
 */
@Composable
private fun DateCandidateList(
    candidates: List<DateCandidate>,
    productionDate: String,
    expireAt: String,
    onFill: (candidate: DateCandidate, asProductionDate: Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (candidates.size == 1) {
                "照片里检出 1 个日期，点选确认填到哪个字段"
            } else {
                "照片里检出 ${candidates.size} 个日期，点选确认填到哪个字段"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (candidate in candidates) {
                // 同一个日期可能两张图里都被认出来，后缀讲清它现在落在哪个字段
                val filledAs = when (candidate.isoDate) {
                    productionDate -> "→ 生产日期"
                    expireAt -> "→ 到期日期"
                    else -> null
                }
                val adopted = filledAs != null
                // 识别阶段判不出角色：把「填到哪」交给用户决定
                val needsChoice = candidate.role == DateRole.unknown
                var menuOpen by remember(candidate) { mutableStateOf(false) }
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (adopted) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
                        else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            if (adopted) 1.5.dp else 1.dp,
                            if (adopted) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.clickable {
                            when {
                                needsChoice -> menuOpen = true
                                candidate.role == DateRole.produce -> onFill(candidate, true)
                                else -> onFill(candidate, false)
                            }
                        },
                    ) {
                        Row(
                            Modifier.padding(
                                start = 10.dp,
                                end = if (needsChoice) 6.dp else 10.dp,
                                top = 6.dp,
                                bottom = 6.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${candidate.isoDate} ${candidate.role.label}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (filledAs != null) {
                                Text(
                                    filledAs,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            if (needsChoice) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "选择填到哪个字段",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("填为生产日期") },
                            onClick = {
                                menuOpen = false
                                onFill(candidate, true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("填为到期日期") },
                            onClick = {
                                menuOpen = false
                                onFill(candidate, false)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 图片排序弹窗（由「排序」按钮触发，不占用缩略图的点击/长按手势）。
 *
 * 按住右侧手柄上下拖动，越过半行高就与相邻项交换，松手即生效，不需要再点保存。
 */
@Composable
private fun ImageSortDialog(
    images: List<ExpiryImage>,
    onMove: (from: Int, to: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 拖动回调里要读最新的顺序，不能用 composition 时捕获的旧列表
    val latestImages by rememberUpdatedState(images)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val rowHeight = 60.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整图片顺序") },
        text = {
            Column {
                Text(
                    "按住右侧手柄上下拖动，第 1 张作为封面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for ((index, image) in images.withIndex()) {
                    key(image.id) {
                        val dragging = draggingId == image.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { if (dragging) translationY = offsetY }
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (dragging) MaterialTheme.colorScheme.surfaceVariant
                                    else Color.Transparent,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(20.dp),
                            )
                            val file = rememberFileForPath(image.path)
                            if (file != null) {
                                DraftImage(
                                    file = file,
                                    size = 42.dp,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (index == 0) "封面" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .pointerInput(image.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggingId = image.id
                                                offsetY = 0f
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                offsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                offsetY = 0f
                                            },
                                        ) { change, dragAmount ->
                                            change.consume()
                                            offsetY += dragAmount.y
                                            val from = latestImages.indexOfFirst { it.id == image.id }
                                            if (from < 0) return@detectDragGestures
                                            when {
                                                offsetY > rowPx / 2f && from < latestImages.lastIndex -> {
                                                    onMove(from, from + 1)
                                                    offsetY -= rowPx
                                                }
                                                offsetY < -rowPx / 2f && from > 0 -> {
                                                    onMove(from, from - 1)
                                                    offsetY += rowPx
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Menu,
                                    contentDescription = "拖动排序",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 重新识别弹窗：先选对象，再执行。
 *
 * 默认全选（多数场景是整批重跑），只想补某一张时逐张取消勾选即可。
 */
@Composable
private fun ReRecognizeDialog(
    images: List<ExpiryImage>,
    onConfirm: (List<ExpiryImage>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(images.map { it.id }.toSet()) }
    val allSelected = images.isNotEmpty() && selected.size == images.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新识别") },
        text = {
            Column {
                Text(
                    "选择要重新识别的图片。结果只补充空着的字段和候选，不会覆盖已填内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selected = if (allSelected) emptySet() else images.map { it.id }.toSet()
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (allSelected) "取消全选" else "全选",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                for ((index, image) in images.withIndex()) {
                    val checked = image.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selected = if (checked) selected - image.id else selected + image.id
                            }
                            .padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selected = if (on) selected + image.id else selected - image.id
                            },
                        )
                        val file = rememberFileForPath(image.path)
                        if (file != null) {
                            DraftImage(
                                file = file,
                                size = 38.dp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("第 ${index + 1} 张", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(images.filter { it.id in selected }) },
            ) { Text("开始识别") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 下拉/日期选择框：白底描边圆角，可选前后缀图标。 */
@Composable
private fun SelectBox(
    text: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(48.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let {
                it()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (text.isEmpty() || text == "选择日期") MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.let {
                Spacer(Modifier.width(6.dp))
                it()
            }
        }
    }
}

/** 数量步进器：− 数字 + 的紧凑横排。 */
@Composable
private fun QuantityStepper(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepCell("−", enabled = value > 1) { onChange(value - 1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("$value", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            StepCell("+", enabled = value < 9999) { onChange(value + 1) }
        }
    }
}

@Composable
private fun StepCell(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 40.dp, height = 48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** 虚线描边修饰符（native Canvas 实现，Compose 官方 border 不支持 dash）。 */
private fun Modifier.dashedBorder(color: Color, cornerRadius: Dp): Modifier = drawBehind {
    val paint = android.graphics.Paint().apply {
        this.color = color.copy(alpha = 0.55f).toArgb()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5.dp.toPx()
        pathEffect = DashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f)
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawRoundRect(
        0f, 0f, size.width, size.height,
        cornerRadius.toPx(), cornerRadius.toPx(), paint,
    )
}
