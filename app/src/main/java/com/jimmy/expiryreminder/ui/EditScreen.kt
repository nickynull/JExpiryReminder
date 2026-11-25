@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jimmy.expiryreminder.ui

import android.app.Activity
import android.graphics.DashPathEffect
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.MAX_IMAGES
import com.jimmy.expiryreminder.core.NameSimilarity
import com.jimmy.expiryreminder.ocr.DateCandidate
import com.jimmy.expiryreminder.ocr.DateExtractor
import com.jimmy.expiryreminder.ocr.DateRole
import com.jimmy.expiryreminder.ocr.NameCandidate
import com.jimmy.expiryreminder.ocr.NameExtractor
import com.jimmy.expiryreminder.ocr.OcrEngine
import com.jimmy.expiryreminder.ocr.PrefillDraft
import com.jimmy.expiryreminder.ocr.ResultMerger
import com.jimmy.expiryreminder.ocr.ShelfLifeCandidate
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.label
import com.jimmy.expiryreminder.core.normalizeImagePath
import com.jimmy.expiryreminder.core.normalizeRemindDays
import com.jimmy.expiryreminder.core.uid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private const val MAX_NOTE = 200

/** 编辑页图片查看器的入参：[origin] 是发起点击的缩略图屏幕位置，用于展开/缩回转场。 */
private data class EditViewerState(
    val index: Int,
    val origin: ImageOrigin?,
)

/** 参考稿样式编辑页：单张白色大卡 + 嵌套有效期分组 + 底部通栏保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    itemId: String?,
    defaultCategory: String? = null,
    /** 主页当前筛选的空间；新增时用它作默认归属，「全部空间」「未分配」为 null。 */
    defaultSpace: String? = null,
    /** 识别预填结果；纯手动新增时为 null。 */
    prefill: PrefillDraft? = null,
    /** 新增时名称撞到已有物品，用户在相似列表里点选后跳去编辑那一条。 */
    onEditExisting: (String) -> Unit = {},
    onDone: () -> Unit,
    /** 保存/删除成功后退出：不应模拟「返回手势」，走平台式淡入淡出。 */
    onSaved: (() -> Unit)? = null,
) {
    val finish = onSaved ?: onDone
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
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
    // 归属空间：编辑已有物品用它的空间；新增时用主页筛选的空间（没有筛选则「不归属空间」）
    var spaceId by remember {
        mutableStateOf(
            existing?.let { store.resolveSpaceId(it.spaceId) }
                ?: defaultSpace?.takeIf { it.isNotEmpty() && store.spaceOf(it) != null }
                ?: ""
        )
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
    // 循环任务的到期日始终由「上次完成日 + 周期」推导，不允许停留在手动指定的旧日期
    var repeatEnabled by remember { mutableStateOf(existing?.repeatEnabled ?: false) }
    // 定点提醒：循环任务的专属提醒时刻（"HH:mm"，空 = 未设置，走全局推送时刻）
    var reminderTime by remember { mutableStateOf(existing?.reminderTime ?: "") }
    var showFixedTimeDialog by remember { mutableStateOf(false) }
    var fixedWheelHour by remember {
        mutableStateOf(existing?.reminderTime?.substringBefore(':')?.toIntOrNull() ?: 22)
    }
    var fixedWheelMinute by remember {
        mutableStateOf(existing?.reminderTime?.substringAfter(':')?.toIntOrNull() ?: 0)
    }
    var manualExpire by remember {
        mutableStateOf(
            if (existing != null) {
                !existing.repeatEnabled && existing.expireAt.isNotEmpty() &&
                    ExpiryCalc.computeExpireAt(
                        existing.productionDate, existing.shelfLife, existing.shelfLifeUnit,
                    ) != existing.expireAt
            } else {
                prefill?.manualExpire ?: false
            }
        )
    }
    // 新增物品时预填该分类的默认提前提醒天数；编辑已有物品永远用物品自己的设置
    var remindDays by remember {
        mutableStateOf(existing?.remindDays ?: store.defaultRemindDaysOf(category))
    }
    // 用户是否手动改过提醒档位：没改过时，新增页切换分类会跟着换成新分类的默认值
    var remindTouched by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf(existing?.quantity ?: 1) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var images by remember { mutableStateOf(existing?.images ?: prefill?.images ?: emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 自定义提前天数的弹窗开关
    var customDayDialog by remember { mutableStateOf(false) }
    // 正在查看的图片：下标 + 发起点击的缩略图位置（用于展开/缩回转场）；null = 查看器未打开
    var viewerState by remember { mutableStateOf<EditViewerState?>(null) }
    // 每张缩略图在屏幕上的位置，布局时记录，点击看大图时取用。
    // 圆角与缩略图一致（12dp），缩回终点才不会形状突变。
    val thumbRadiusPx = with(LocalDensity.current) { 12.dp.toPx() }
    val thumbOrigins = remember { mutableStateMapOf<Int, ImageOrigin>() }

    // ---------------------------------------------- 相似物品（防重复录入）
    // 只在新增模式检索：编辑已有物品时改名撞到别人，不算「重复录入」，不该打扰。
    var similarMatches by remember { mutableStateOf<List<NameSimilarity.Match>>(emptyList()) }
    var similarDialog by remember { mutableStateOf(false) }
    LaunchedEffect(name) {
        if (existing != null ||
            NameSimilarity.normalize(name).length < NameSimilarity.MIN_QUERY_LENGTH
        ) {
            similarMatches = emptyList()
            return@LaunchedEffect
        }
        // 逐字输入时每敲一下都全量比对没有意义，停手 350ms 再查一次
        delay(350)
        similarMatches = NameSimilarity.findSimilar(store.activeItems(), name)
    }

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

    // 备注框是多行且靠下：输入法弹出时 imePadding 只收缩布局，不会自动把焦点框滚进视口，
    // 这里在它获焦后主动滚一次，避免光标落在备注上却被键盘盖住。
    var noteFocused by remember { mutableStateOf(false) }
    val noteBringIntoView = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    LaunchedEffect(noteFocused) {
        if (noteFocused) {
            delay(220) // 等输入法弹出、布局稳定后再滚，否则滚早了会被随后重排抵消
            noteBringIntoView.bringIntoView()
            // bringIntoView 只滚到视口底缘，而底部悬浮的保存栏会盖住备注框下半截，
            // 再往下补一段让整个备注框落在保存栏上方。
            scrollState.animateScrollTo(
                scrollState.value + with(density) { 88.dp.toPx() }.toInt()
            )
        }
    }

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

    // 自动推算到期日（循环任务按「起始日 + 周期」推进，不减一天）
    LaunchedEffect(productionDate, shelfLifeText, unit, manualExpire, repeatEnabled) {
        if (!manualExpire) {
            val amount = shelfLifeText.toIntOrNull() ?: 0
            expireAt = if (repeatEnabled) {
                ExpiryCalc.computeCycleExpireAt(productionDate, amount, unit)
            } else {
                ExpiryCalc.computeExpireAt(productionDate, amount, unit)
            }
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

    // 备注取字：拍照 → 落盘成临时图（不进物品图集）→ 圈选/整图识别 → 回填备注
    var noteCameraTarget by remember { mutableStateOf<File?>(null) }
    var noteOcrPath by remember { mutableStateOf<String?>(null) }
    val noteTakePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = noteCameraTarget
        if (ok && file != null && file.exists()) {
            scope.launch(Dispatchers.IO) {
                val path = runCatching {
                    ImageStore.saveCompressedImage(context, file.readBytes(), "note_${uid()}")
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (path != null) noteOcrPath = path else showError("拍照失败，请重试")
                }
                runCatching { file.delete() }
            }
        }
        noteCameraTarget = null
    }
    fun launchNoteCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.jpg")
        noteCameraTarget = file
        val authority = "${context.packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        noteTakePicture.launch(uri)
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

    // 日期选择器：datePickerTarget 决定日期回填生产日期还是到期日期
    var showDatePicker by remember { mutableStateOf(false) }
    var datePickerTarget by remember { mutableStateOf("expire") } // expire | production
    // 滚轮里的临时日期，点「确定」才写回字段
    var pickerDate by remember { mutableStateOf(LocalDate.now()) }
    // 图片添加来源菜单
    var imageSourceMenu by remember { mutableStateOf(false) }
    // 分类/单位下拉
    var categoryMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var spaceMenu by remember { mutableStateOf(false) }

    // 图片：拖动排序 / 重新识别 / 圈选识别（都由按钮触发）
    var sortDialog by remember { mutableStateOf(false) }
    var reOcrDialog by remember { mutableStateOf(false) }
    var reOcrRunning by remember { mutableStateOf(false) }
    var reOcrDone by remember { mutableStateOf(0) }
    var reOcrTotal by remember { mutableStateOf(0) }
    var cropOcrOpen by remember { mutableStateOf(false) }

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

    // 保存：校验 -> 组装 -> 入库；提示走底部 Snackbar，同时把视线滚回顶部
    fun save() {
        val finalName = name.trim()
        if (finalName.isEmpty()) {
            showError("请填写物品名称")
            scope.launch { scrollState.animateScrollTo(0) }
            return
        }
        val amount = shelfLifeText.toIntOrNull() ?: 0
        if (repeatEnabled) {
            if (!Dates.isValidISODate(productionDate)) {
                showError("请选择上次完成日期")
                scope.launch { scrollState.animateScrollTo(0) }
                return
            }
            if (amount <= 0) {
                showError("请填写循环周期")
                scope.launch { scrollState.animateScrollTo(0) }
                return
            }
            // 循环任务的到期日只认「上次完成日 + 周期」（不减一天），忽略任何可能残留的手动日期
            expireAt = ExpiryCalc.computeCycleExpireAt(productionDate, amount, unit)
        }
        if (expireAt.isEmpty()) {
            showError("请填写到期日期（生产日期 + 有效期，或手动指定）")
            scope.launch { scrollState.animateScrollTo(0) }
            return
        }
        if (!Dates.isValidISODate(expireAt)) {
            showError("到期日期格式不正确")
            return
        }
        val draft = ExpiryItem(
            id = existing?.id ?: "",
            name = finalName,
            category = category,
            productionDate = productionDate,
            shelfLife = amount,
            shelfLifeUnit = unit,
            expireAt = expireAt,
            remindDays = remindDays,
            quantity = quantity.coerceIn(1, MAX_QUANTITY),
            note = note.trim(),
            images = images,
            notifiedKeys = existing?.notifiedKeys ?: emptyList(),
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: Dates.nowIsoTimestamp(),
            updatedAt = Dates.nowIsoTimestamp(),
            spaceId = store.resolveSpaceId(spaceId),
            repeatEnabled = repeatEnabled,
            reminderTime = reminderTime.takeIf { repeatEnabled && it.isNotBlank() } ?: "",
        )
        scope.launch {
            if (existing == null) store.createItem(context, draft)
            else store.updateItem(context, existing.id, draft)
            finish()
        }
    }

    // 页面底：整页铺「底色」，表单卡片才是纯白。之前这里没画底，
    // 一直沿用根部 Surface 的白（=卡片色），白卡落在白底上只能靠一条浅描边硬撑边界。
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // 紧凑头部：单行标题，副标题说明去掉，把纵向空间全部让给表单
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDone, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        if (existing == null) "新增物品" else "编辑物品",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 88.dp),
            ) {
                // ============================================== 表单大卡（白底页面靠描边区分）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .align(Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FormCard {
                        // -------------------------------------- 物品名称
                        // 标签行右侧挂相似物品徽标：输入框内右侧已被「清空 ×」占着，
                        // 两个小按钮并排容易误触，所以挪到框外标签行右端。
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FieldLabel("物品名称", ocrBadge = nameFromOcr)
                            if (similarMatches.isNotEmpty()) {
                                Spacer(Modifier.weight(1f))
                                SimilarNameBadge(
                                    count = similarMatches.size,
                                    sameName = similarMatches.any { it.sameName },
                                    onClick = {
                                        focusManager.clearFocus()
                                        similarDialog = true
                                    },
                                )
                            }
                        }
                        InputBox(
                            value = name,
                            onValueChange = { name = it; nameFromOcr = false },
                            placeholder = "如：维生素 C、护照、牛奶",
                            fixedHeight = 44.dp,
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = nameFocus,
                            showClear = true,
                        )

                        // 识别原文：离线 OCR 不准时可逐行核对，点一行补进备注
                        val rawLines = prefill?.rawLines.orEmpty()
                        if (rawLines.isNotEmpty()) {
                            var showRaw by remember { mutableStateOf(false) }
                            TextButton(onClick = { showRaw = !showRaw }) {
                                Text(
                                    if (showRaw) "收起识别原文（${rawLines.size} 行）"
                                    else "查看识别原文（${rawLines.size} 行）",
                                )
                            }
                            if (showRaw) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(8.dp),
                                ) {
                                    rawLines.forEach { line ->
                                        Text(
                                            line,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    note = (if (note.isBlank()) line else "$note $line")
                                                        .take(MAX_NOTE)
                                                }
                                                .padding(vertical = 3.dp),
                                        )
                                    }
                                    Text(
                                        "点任意一行可追加到备注",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

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

                        // -------------------------------------- 空间 + 分类
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
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
                            }
                            Column(Modifier.weight(1f)) {
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
                                                    // 新增物品且用户还没手动挑过档位：跟随分类换成它的默认值
                                                    if (existing == null && !remindTouched) {
                                                        remindDays = store.defaultRemindDaysOf(cat.id)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
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

                        // -------------------------------------- 循环任务开关
                        // 充电 / 清理 / 添置这类物品到期只是「该做一次了」，完成后从完成日重新计时。
                        fun toggleRepeat(next: Boolean) {
                            repeatEnabled = next
                            // 循环模式不允许停留在手动指定的到期日；关闭时保留当前日期作为手动值
                            manualExpire = !next
                            // 关闭循环后定点提醒没有意义，一并清掉
                            if (!next) reminderTime = ""
                            if (next) {
                                if (!Dates.isValidISODate(productionDate)) {
                                    productionDate = Dates.todayISO()
                                }
                                val amount = shelfLifeText.toIntOrNull() ?: 0
                                if (amount <= 0) {
                                    shelfLifeText = "30"
                                    unit = ShelfLifeUnit.day
                                }
                                expireAt = ExpiryCalc.computeCycleExpireAt(
                                    productionDate, shelfLifeText.toIntOrNull() ?: 30, unit,
                                )
                            }
                        }
                        // 与其它字段同一套「白底 + 描边」语言，不再用灰色实底块打断表单节奏
                        Surface(
                            onClick = { toggleRepeat(!repeatEnabled) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "循环任务",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        if (repeatEnabled) "完成后自动从完成日重新计时"
                                        else "适合充电、清理、添置等定期事项",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                AppSwitch(checked = repeatEnabled, onCheckedChange = { toggleRepeat(it) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // -------------------------------------- 上次完成日 / 周期 / 单位
                        // 循环模式下复用这三个字段：上次完成日（productionDate）+ 周期（shelfLife + unit）
                        // 外层按左右两半分：生产日期占左半（与到期日期对齐），
                        // 右半内部再按 0.55:0.45 分给有效期与单位，保证有效期列与分类/数量列起始对齐
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                FieldLabel(
                                    if (repeatEnabled) "上次完成日" else "生产日期",
                                    optional = true,
                                    ocrBadge = productionFromOcr,
                                )
                                SelectBox(
                                    text = productionDate.ifEmpty { "选择日期" },
                                    onClick = {
                                        datePickerTarget = "production"
                                        pickerDate = runCatching { LocalDate.parse(productionDate) }
                                            .getOrNull() ?: LocalDate.now()
                                        showDatePicker = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(0.55f)) {
                                        FieldLabel(
                                            if (repeatEnabled) "周期" else "有效期",
                                            optional = true,
                                            ocrBadge = shelfLifeFromOcr,
                                        )
                                        InputBox(
                                            value = shelfLifeText,
                                            onValueChange = {
                                                shelfLifeText = it.filter { c -> c.isDigit() }.take(5)
                                                shelfLifeFromOcr = false
                                            },
                                            placeholder = "选填",
                                            fixedHeight = 44.dp,
                                            modifier = Modifier.fillMaxWidth(),
                                            showClear = true,
                                            keyboardType = KeyboardType.Number,
                                        )
                                    }
                                    Column(Modifier.weight(0.45f)) {
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
                            }
                        }
                        // -------------------------------------- 到期日期 + 数量
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                FieldLabel(
                                    if (repeatEnabled) "本周期到期" else "到期日期",
                                    ocrBadge = expireFromOcr && !repeatEnabled,
                                )
                                if (repeatEnabled) {
                                    // 循环到期日由「上次完成日 + 周期」推导，此处只读，避免手动改完与周期对不上
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                expireAt.ifEmpty { "请先填完成日和周期" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (expireAt.isEmpty()) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                } else {
                                    SelectBox(
                                        text = expireAt.ifEmpty { "选择日期" },
                                        leading = {
                                            Icon(
                                                Icons.Filled.DateRange, contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        onClick = {
                                            datePickerTarget = "expire"
                                            pickerDate = runCatching { LocalDate.parse(expireAt) }
                                                .getOrNull() ?: LocalDate.now()
                                            showDatePicker = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
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
                        // 剩余天数提示只在日期已填时出现；没填时这行纯属占位，删掉省一行
                        if (expireAt.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (repeatEnabled) "本周期${Dates.describeRemaining(expireAt)}"
                                    else Dates.describeRemaining(expireAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!repeatEnabled && manualExpire && productionDate.isNotEmpty() &&
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

                        // -------------------------------------------- 提醒与补充
                        // 提前提醒

                        // -------------------------------------- 提前提醒
                        FieldLabel("提前提醒")
                        RemindDayChips(
                            selected = remindDays,
                            onToggle = { day ->
                                remindTouched = true
                                remindDays =
                                    if (day in remindDays) remindDays - day else remindDays + day
                            },
                            onCustom = { customDayDialog = true },
                            chipHeight = 26.dp,
                            compactLabel = true,
                        )

                        // -------------------------------------- 定点提醒（循环任务专属）
                        if (repeatEnabled) {
                            FieldLabel("定点提醒", optional = true)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (reminderTime.isNotBlank()) "每个提醒日 $reminderTime 提醒" else "按提前天数档位触发",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            if (reminderTime.isNotBlank()) "未勾档位时默认当天提醒"
                                            else "关闭时沿用设置页的统一推送时刻",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (reminderTime.isNotBlank()) {
                                        Text(
                                            "修改",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    fixedWheelHour =
                                                        reminderTime.substringBefore(':')
                                                            .toIntOrNull() ?: 22
                                                    fixedWheelMinute =
                                                        reminderTime.substringAfter(':')
                                                            .toIntOrNull() ?: 0
                                                    showFixedTimeDialog = true
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    AppSwitch(
                                        checked = reminderTime.isNotBlank(),
                                        onCheckedChange = { on ->
                                            if (on) {
                                                showFixedTimeDialog = true
                                            } else {
                                                reminderTime = ""
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        // -------------------------------------- 备注
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FieldLabel("备注", optional = true)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${note.length} / $MAX_NOTE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        InputBox(
                            value = note,
                            onValueChange = { note = it.take(MAX_NOTE) },
                            placeholder = "存放位置、开封日期等",
                            // 默认单行；换行/粘贴多行时随内容撑开，删减后自动缩回，
                            // animateContentSize 让展开/收起不跳变
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize()
                                .bringIntoViewRequester(noteBringIntoView)
                                .onFocusChanged { noteFocused = it.isFocused },
                            showClear = true,
                            trailing = {
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable { launchNoteCamera() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CameraGlyph(
                                        color = MaterialTheme.colorScheme.primary,
                                        holeColor = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
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
                                Text(
                                    "圈选识别",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { cropOcrOpen = true }
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
                                    .size(64.dp)
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
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.height(2.dp))
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
                                        .onGloballyPositioned { coords ->
                                            val pos = coords.positionInWindow()
                                            thumbOrigins[imgIndex] = ImageOrigin(
                                                x = pos.x,
                                                y = pos.y,
                                                width = coords.size.width.toFloat(),
                                                height = coords.size.height.toFloat(),
                                                cornerRadius = thumbRadiusPx,
                                            )
                                        }
                                        .clickable {
                                            viewerState = EditViewerState(imgIndex, thumbOrigins[imgIndex])
                                        },
                                ) {
                                    val file = rememberFileForPath(img.path)
                                    if (file != null) {
                                        DraftImage(
                                            file = file, size = 64.dp,
                                            modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                                        )
                                    } else {
                                        Box(
                                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
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
            }

            // ---------------------------------------------- 底部悬浮保存按钮
            // 主页查询框同款磨砂胶囊：底部不再铺底板和分割线，表单从按钮底下穿过；
            // 阴影由 frostedPill 四周留出，比原来的实心蓝底按钮更有立体感。
            // navigationBarsPadding 把按钮整体抬到手势横条之上，不再压住系统小横条
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(52.dp)
                    .frostedPill(RoundedCornerShape(26.dp))
                    .clickable { save() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "保存",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            }
        }

        // 底部浮层提示：贴在保存栏上方，几秒后自动消失，不占页面高度
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .padding(bottom = 92.dp),
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
        // 和「推送时刻」同款自绘对话框：滚轮中心行可点，数字键盘弹起时靠 imePadding 让位
        Dialog(
            onDismissRequest = { showDatePicker = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text(
                            if (datePickerTarget == "production") "选择生产日期" else "选择到期日期",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Text(
                            "滑动选择，或在下方输入框直接输入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        DateWheelPicker(
                            date = pickerDate,
                            onDateChange = { pickerDate = it },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                            TextButton(onClick = {
                                // 滚轮里可能正在输入（只敲了一位还没自动提交），
                                // 先让它失焦把草稿交上来，等一帧再读，否则会存到旧值
                                focusManager.clearFocus()
                                scope.launch {
                                    withFrameNanos { }
                                    val text = pickerDate.toString()
                                    if (datePickerTarget == "production") {
                                        productionDate = text
                                        productionFromOcr = false
                                    } else {
                                        expireAt = text
                                        manualExpire = true
                                        expireFromOcr = false
                                    }
                                    showDatePicker = false
                                }
                            }) { Text("确定") }
                        }
                    }
                }
            }
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
                        finish()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    // 相似物品：只提示不阻断——同款不同批次（两箱同品牌牛奶）确实可能各录一条
    if (similarDialog && similarMatches.isNotEmpty()) {
        SimilarItemsDialog(
            matches = similarMatches,
            store = store,
            onEdit = { id ->
                similarDialog = false
                focusManager.clearFocus()
                onEditExisting(id)
            },
            onDismiss = { similarDialog = false },
        )
    }

    // 自定义提前天数：确认后并入档位（越界/重复在弹窗内拦下）
    if (customDayDialog) {
        CustomRemindDayDialog(
            remindDays = remindDays,
            onConfirm = { day ->
                remindTouched = true
                remindDays = normalizeRemindDays(remindDays + day)
                customDayDialog = false
            },
            onDismiss = { customDayDialog = false },
        )
    }

    // 定点提醒时刻：滚轮选择，与设置页推送时刻同一套交互
    if (showFixedTimeDialog) {
        Dialog(
            onDismissRequest = { showFixedTimeDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text(
                            "选择定点提醒时刻",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Text(
                            "该循环任务的每个提醒日将在这一时刻提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        TimeWheelPicker(
                            hour = fixedWheelHour,
                            minute = fixedWheelMinute,
                            onHourChange = { fixedWheelHour = it },
                            onMinuteChange = { fixedWheelMinute = it },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showFixedTimeDialog = false }) { Text("取消") }
                            TextButton(onClick = {
                                // 先让滚轮失焦把草稿交上来，等一帧再读，否则会存到旧值
                                focusManager.clearFocus()
                                scope.launch {
                                    withFrameNanos { }
                                    reminderTime = "%02d:%02d".format(fixedWheelHour, fixedWheelMinute)
                                    showFixedTimeDialog = false
                                }
                            }) { Text("确定") }
                        }
                    }
                }
            }
        }
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

    // 圈选识别：框住照片上的日期/品名区域，放大重识后一键填字段
    if (cropOcrOpen && images.isNotEmpty()) {
        CropOcrOverlay(
            paths = images.map { normalizeImagePath(it.path) }.filter { it.isNotEmpty() },
            onDismiss = { cropOcrOpen = false },
            onPickName = {
                name = it.trim()
                if (name.isNotEmpty()) nameFromOcr = true
            },
            onPickProduction = {
                productionDate = it
                productionFromOcr = true
            },
            onPickExpire = {
                expireAt = it
                manualExpire = true
                expireFromOcr = true
            },
            onPickShelfLife = { amount, unitValue ->
                shelfLifeText = amount.toString()
                unit = unitValue
                shelfLifeFromOcr = true
            },
            onPickNote = { text ->
                note = (if (note.isBlank()) text else "$note\n$text").take(MAX_NOTE)
            },
        )
    }

    // 备注取字：相机拍照后圈选/整图识别，识别文字回填备注（临时图不进物品图集）
    noteOcrPath?.let { p ->
        CropOcrOverlay(
            paths = listOf(p),
            onDismiss = {
                noteOcrPath = null
                runCatching { ImageStore.imageFile(context, p).delete() }
            },
            onPickName = {},
            onPickProduction = {},
            onPickExpire = {},
            onPickShelfLife = { _, _ -> },
            onPickNote = { text ->
                note = (if (note.isBlank()) text else "$note\n$text").take(MAX_NOTE)
            },
            noteOnly = true,
        )
    }

    // 全屏图片查看器：编辑中的草稿图片也能看（文件在入库时就已落盘）
    viewerState?.let { state ->
        ImageViewerOverlay(
            paths = images.map { it.path },
            initialIndex = state.index,
            origin = state.origin,
            onDismiss = { viewerState = null },
        )
    }
}

// ---------------------------------------------------------------- 私有小组件

/** 字段标签：黑字 + 可选的「选填」灰字。 */
@Composable
private fun FieldLabel(text: String, optional: Boolean = false, ocrBadge: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
 * 相似物品徽标：挂在「物品名称」标签行右端。
 *
 * 只在名称够长、且真的查到相似项时才出现；同名用错误色（大概率是重复录入），
 * 只是相似用第三色（可能是同款不同批次，未必是错）。
 */
@Composable
private fun SimilarNameBadge(count: Int, sameName: Boolean, onClick: () -> Unit) {
    val accent = if (sameName) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (sameName) "同名 $count 个" else "相似 $count 个",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 相似物品弹窗：按相似度排序的候选列表，点一条直接去编辑它。
 *
 * 保留「继续新增」而不是只给「确定」：同款第二箱牛奶本来就该单独录，
 * 这里只是提醒，不能替用户做决定。
 */
@Composable
private fun SimilarItemsDialog(
    matches: List<NameSimilarity.Match>,
    store: ExpiryStore,
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "已有 ${matches.size} 个相似物品",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "按名称相似度排序，点一条去编辑它",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (match in matches) {
                        SimilarItemRow(
                            match = match,
                            store = store,
                            onClick = { onEdit(match.item.id) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "点选后将打开该物品，本次新增不会保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("继续新增") }
                }
            }
        }
    }
}

/** 相似物品列表的一行：名称 + 分类色点 + 空间 + 到期日 + 状态。 */
@Composable
private fun SimilarItemRow(
    match: NameSimilarity.Match,
    store: ExpiryStore,
    onClick: () -> Unit,
) {
    val item = match.item
    val status = store.statusOf(item)
    val spaceId = store.resolveSpaceId(item.spaceId)
    // 未分配空间时整段不提空间，与主页卡片一致（不显示默认态）
    val meta = buildList {
        add(store.categoryLabelOf(item.category))
        if (spaceId.isNotEmpty()) add(store.spaceLabelOf(spaceId))
        add(item.expireAt.ifEmpty { "未设到期日" })
    }.joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (match.sameName) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "同名",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(hexColor(store.categoryColorOf(item.category)), CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = hexColor(status.color),
                )
            }
        }
    }
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(44.dp),
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

/** 数量上限：与步进器「+」的禁用阈值、保存时的兜底归一保持同一处定义。 */
private const val MAX_QUANTITY = 9999

/**
 * 数量步进器：− [数字输入框] + 的紧凑横排。
 *
 * 中间是真输入框（数字键盘），既能点 +/− 微调，也能直接敲数字。
 * 编辑态用本地字符串保存、允许中途为空，失焦或按「完成」时统一归一：
 * 空/0 → 1，超过 [MAX_QUANTITY] → 截到上限。
 *
 * 注意：点 +/− 时输入框通常仍持有焦点，[LaunchedEffect] 里的「未聚焦才回填」不会生效，
 * 所以 ± 必须自己调 [apply] 同步文本框——否则值变了、数字不变，看着像按钮失灵。
 */
@Composable
private fun QuantityStepper(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val qtyFocus = remember { FocusRequester() }
    val qtyInteraction = remember { MutableInteractionSource() }
    // 与外部 Int 解耦的编辑文本：输入中途「0」「空」都是合法中间态，别被强行回填
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }

    // 外部值变化（载入旧数据、OCR 预填等）且用户不在输入时，同步回文本框
    LaunchedEffect(value) {
        if (!focused && text.toIntOrNull() != value) text = value.toString()
    }

    /** 当前显示值：以输入框里的内容为准（空则退回已生效值），并夹到合法区间。 */
    fun shownValue(): Int = text.toIntOrNull()?.coerceIn(1, MAX_QUANTITY) ?: value

    /** 写值并立刻同步文本框（± 的入口）。 */
    fun apply(newValue: Int) {
        val v = newValue.coerceIn(1, MAX_QUANTITY)
        text = v.toString()
        if (v != value) onChange(v)
    }

    /** 失焦 / 按「完成」时归一：空或 0 视为 1。 */
    fun commit() {
        val parsed = (text.toIntOrNull() ?: 0).coerceIn(1, MAX_QUANTITY)
        text = parsed.toString()
        if (parsed != value) onChange(parsed)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.height(44.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 边界按「看到的值」判断，输入框里的中间态也一并照顾到
            StepCell("−", enabled = shownValue() > 1) { apply(shownValue() - 1) }
            // 中间整列都可点（不靠 24dp 的文字高度当命中区），点了就把焦点给输入框
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = qtyInteraction,
                        indication = null,
                    ) { qtyFocus.requestFocus() },
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { raw ->
                        // 只收数字、最多 4 位；合法值实时写回，空/0 留给 commit() 归一
                        val digits = raw.filter { c -> c.isDigit() }.take(4)
                        text = digits
                        val parsed = digits.toIntOrNull()
                        if (parsed != null && parsed >= 1 && parsed != value) onChange(parsed)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        commit()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(qtyFocus)
                        .onFocusChanged {
                            // 失焦时归一（注意顺序：先提交再更新 focused 标记）
                            if (focused && !it.isFocused) commit()
                            focused = it.isFocused
                        },
                )
            }
            StepCell("+", enabled = shownValue() < MAX_QUANTITY) { apply(shownValue() + 1) }
        }
    }
}

@Composable
private fun StepCell(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 36.dp, height = 44.dp)
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

// frostedPill 已提取到 Common.kt 作为全站主按钮胶囊，编辑页/选图页/裁剪页共用

/** 虚线描边修饰符（native Canvas 实现，Compose 官方 border 不支持 dash）。 */
private fun Modifier.dashedBorder(color: Color, cornerRadius: Dp): Modifier = drawBehind {    val paint = android.graphics.Paint().apply {
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

/**
 * 分组卡片：纯白卡片 + 全站统一的描边/柔投影（[frostedPanel]），浮在页面底色上。
 *
 * 为什么不再用 `Surface(color = surface, tonalElevation = 1.dp)`：
 * 编辑页的页面底就是底色（#F8F8F8），卡片必须靠「纯白 + 边界」跳出来；Surface 那套
 * 只给一条 0.7 透明的 outlineVariant 描边，落在底色上几乎看不见，卡片会和页面糊成一片。
 */
@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // 全站内容卡统一：实心白 + 柔影无描边（Common.contentCard），2026-11-25 起
            // 不再走 frostedPanel 的描边版（与主页物品卡/设置页/传输页同一套浮起语言）
            .contentCard(RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 表单分组标题：主色文字 + 细分割线；实现收拢到 Common.SectionHeader 全站共用。 */
@Composable
private fun EditSectionHeader(title: String) {
    SectionHeader(title, Modifier.fillMaxWidth().padding(top = 2.dp))
}
