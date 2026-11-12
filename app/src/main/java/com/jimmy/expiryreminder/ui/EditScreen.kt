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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryCalc
import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.MAX_IMAGES
import com.jimmy.expiryreminder.core.REMIND_DAY_OPTIONS
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.label
import com.jimmy.expiryreminder.core.remindDayLabel
import com.jimmy.expiryreminder.core.uid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val MAX_NOTE = 200

/** 参考稿样式编辑页：单张白色大卡 + 嵌套有效期分组 + 底部通栏保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(itemId: String?, defaultCategory: String? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = ExpiryStore.get()
    val scope = rememberCoroutineScope()
    val existing = itemId?.let { store.getItem(it) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    // 新增时优先用主页当前的筛选分类，无筛选（或分类已不存在）则默认「食品」
    var category by remember {
        mutableStateOf(
            existing?.category
                ?: defaultCategory?.takeIf { store.categoryOf(it) != null }
                ?: Categories.NEW_ITEM_DEFAULT
        )
    }
    var productionDate by remember { mutableStateOf(existing?.productionDate ?: "") }
    var shelfLifeText by remember {
        mutableStateOf(existing?.shelfLife?.takeIf { it > 0 }?.toString() ?: "")
    }
    var unit by remember { mutableStateOf(existing?.shelfLifeUnit ?: ShelfLifeUnit.month) }
    var expireAt by remember { mutableStateOf(existing?.expireAt ?: "") }
    var manualExpire by remember {
        mutableStateOf(existing != null && existing.expireAt.isNotEmpty() &&
                ExpiryCalc.computeExpireAt(existing.productionDate, existing.shelfLife, existing.shelfLifeUnit) != existing.expireAt)
    }
    var remindDays by remember { mutableStateOf(existing?.remindDays ?: emptyList()) }
    var quantity by remember { mutableStateOf(existing?.quantity ?: 1) }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var images by remember { mutableStateOf(existing?.images ?: emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 正在查看的图片下标：null = 查看器未打开
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // 未填项/图片读取错误统一走「自动消失」的底部提示，不再占用页面顶部空间
    val snackbar = remember { SnackbarHostState() }
    fun showError(msg: String) {
        scope.launch {
            // 连续点击保存时不排队，直接用最新提示覆盖
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }
    val scrollState = rememberScrollState()

    // 新增时自动聚焦名称输入框并弹出键盘，省掉一次点击
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (existing == null) {
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
                        FieldLabel("物品名称")
                        InputBox(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "如：维生素 C、护照、牛奶",
                            fixedHeight = 48.dp,
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = nameFocus,
                        )

                        // -------------------------------------- 分类 + 数量
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1.4f)) {
                                FieldLabel("分类")
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
                                                onClick = { category = cat.id; categoryMenu = false },
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
                                    FieldLabel("生产日期", optional = true)
                                    SelectBox(
                                        text = productionDate.ifEmpty { "选择日期" },
                                        onClick = {
                                            datePickerTarget = "production"; showDatePicker = true
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Column(Modifier.weight(1.2f)) {
                                    FieldLabel("有效期", optional = true)
                                    InputBox(
                                        value = shelfLifeText,
                                        onValueChange = { shelfLifeText = it.filter { c -> c.isDigit() }.take(5) },
                                        placeholder = "选填",
                                        fixedHeight = 48.dp,
                                        modifier = Modifier.fillMaxWidth(),
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
                            FieldLabel("到期日期")
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
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (day in REMIND_DAY_OPTIONS) {
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
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer,
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
                        } else {
                            expireAt = date.toString()
                            manualExpire = true
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
private fun FieldLabel(text: String, optional: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        if (optional) {
            Spacer(Modifier.width(6.dp))
            Text(
                "选填",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 统一的描边输入框：内边距 12dp（无浮空标签，标签在框外），单行时文字垂直居中不裁切。 */
@Composable
private fun InputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    fixedHeight: androidx.compose.ui.unit.Dp? = null,
    focusRequester: FocusRequester? = null,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier)
                .padding(horizontal = 12.dp, vertical = if (fixedHeight != null) 0.dp else 12.dp),
            contentAlignment = if (fixedHeight != null) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = fixedHeight != null,
                minLines = if (fixedHeight != null) 1 else minLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester)
                        else Modifier
                    ),
            )
        }
    }
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
