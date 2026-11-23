package com.jimmy.expiryreminder.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * 全站统一的描边输入框：10dp 圆角 + 12dp 内边距（无浮空标签，标签在框外）。
 *
 * 首页搜索、编辑页各字段、分类管理页新增/改名都用它，尺寸天然一致：
 * [fixedHeight] 非空时按单行处理（48dp 是主用法），为空时按多行自增高度；
 * [showClear] 打开且内容非空时右侧出现小圆叉，一键清空；
 * [isError] 打开时描边转红（错误文案由调用方放在框外）；
 * [keyboardType] 决定弹出哪种输入法键盘——纯数字字段传 [KeyboardType.Number]（默认仍是文本键盘，
 * 不影响不传该参数的调用方）。
 */
@Composable
internal fun InputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    fixedHeight: Dp? = null,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    showClear: Boolean = false,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    InputBoxShell(
        isEmpty = value.isEmpty(),
        onClear = { onValueChange("") },
        placeholder = placeholder,
        modifier = modifier,
        fixedHeight = fixedHeight,
        leading = leading,
        showClear = showClear,
        isError = isError,
        trailing = trailing,
    ) { textModifier ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = fieldTextStyle(),
            cursorBrush = cursorBrush(),
            singleLine = fixedHeight != null,
            minLines = if (fixedHeight != null) 1 else minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = textModifier.then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            ),
        )
    }
}

/**
 * [TextFieldValue] 版：改名弹窗要「预填原名并全选」，[String] 版带不了选区，
 * 只能走这版（否则重新输入会接在旧名字后面）。
 */
@Composable
internal fun InputBox(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    fixedHeight: Dp? = null,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    showClear: Boolean = false,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    InputBoxShell(
        isEmpty = value.text.isEmpty(),
        onClear = { onValueChange(TextFieldValue("")) },
        placeholder = placeholder,
        modifier = modifier,
        fixedHeight = fixedHeight,
        leading = leading,
        showClear = showClear,
        isError = isError,
        trailing = trailing,
    ) { textModifier ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = fieldTextStyle(),
            cursorBrush = cursorBrush(),
            singleLine = fixedHeight != null,
            minLines = if (fixedHeight != null) 1 else minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = textModifier.then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            ),
        )
    }
}

@Composable
private fun fieldTextStyle() = MaterialTheme.typography.bodyMedium.copy(
    color = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun cursorBrush() = SolidColor(MaterialTheme.colorScheme.primary)

/** 输入框的「壳」：描边、内边距、占位符、清空按钮；中间真正的文本框由调用方塞进来。 */
@Composable
private fun InputBoxShell(
    isEmpty: Boolean,
    onClear: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fixedHeight: Dp? = null,
    leading: (@Composable () -> Unit)? = null,
    showClear: Boolean = false,
    isError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    field: @Composable (Modifier) -> Unit,
) {
    val singleLine = fixedHeight != null
    val withClear = showClear && !isEmpty
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier)
                .padding(
                    start = if (leading != null) 10.dp else 12.dp,
                    // 有清空按钮时右侧留白交给按钮自己，避免 X 贴边
                    end = if (withClear || trailing != null) 4.dp else 12.dp,
                )
                .then(if (singleLine) Modifier else Modifier.padding(vertical = 12.dp)),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            leading?.let {
                it()
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier.weight(1f),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (isEmpty) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field(Modifier.fillMaxWidth())
            }
            if (withClear) {
                ClearFieldButton(onClick = onClear)
            }
            trailing?.let {
                if (!withClear) Spacer(Modifier.width(4.dp))
                it()
            }
        }
    }
}

/**
 * 全站「浮起面板」底：半透明面板 + 柔投影 + 一条能看清边界的立体描边。
 *
 * 透明度：默认 0.95，按钮背后的内容只剩极淡的轮廓，按钮文字干净可读；
 * 需要承载数字/正文的地方（统计卡）由调用方传更高的 [fillAlpha] 保对比度。
 *
 * 描边为什么用渐变而不是白色亮边：白亮边落在浅色背景上等于没有边界。这里整圈走
 * outlineVariant 灰线（上 0.62 → 中 0.82 → 底 0.95），四边都看得见；靠深浅差异做厚度，
 * 上边略浅、下边最深，面板仍然是「压」在内容上的感觉。再配合 [Modifier.shadow] 起浮。
 *
 * 为什么不直接用 `Surface(color = 半透明, shadowElevation = x)`：
 * M3 的 Surface 会把容器色当作阴影底板一起画，颜色带 alpha 时这块底板就从圆角外
 * 透出一片**白色矩形**（统计卡、分类行、底部控件都中过招）。这里改成
 * `Modifier.shadow` 显式给一组中性阴影色，再单独叠背景层和描边，绕开这个问题。
 *
 * 末尾的 [Modifier.clip] 让水波纹和子内容都收在圆角里，阴影因为 `clip = false` 不被切掉。
 */
@Composable
internal fun Modifier.frostedPanel(
    shape: Shape = RoundedCornerShape(18.dp),
    elevation: Dp = 12.dp,
    fill: Color = MaterialTheme.colorScheme.surface,
    fillAlpha: Float = 0.95f,
    borderColor: Color? = null,
    borderWidth: Dp = 1.2.dp,
): Modifier {
    val outline = MaterialTheme.colorScheme.outlineVariant
    // 传了 borderColor 就整圈单色（多选态的警示红），否则用上亮下暗的立体渐变。
    // 中段和底部都压得很深：容器全透之后，这条灰线是区分按钮边界的唯一依靠。
    val edge: Brush = borderColor?.let { color ->
        Brush.verticalGradient(
            0f to color.copy(alpha = 0.55f),
            1f to color,
        )
    } ?: Brush.verticalGradient(
        // 上边界也必须是能看见的细线：之前这里用纯白高光，落在浅色背景上等于没有边界，
        // 按钮顶部完全糊在页面里（用户反馈「按钮上边界无法辨认」）。
        // 整圈都用 outlineVariant，只靠深浅做立体：上浅 → 中深 → 底最深。
        0f to outline.copy(alpha = 0.62f),
        0.45f to outline.copy(alpha = 0.82f),
        1f to outline.copy(alpha = 0.95f),
    )
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = Color(0x14000000),
            // 主阴影略收、方向明确：面板浮起来的同时底缘不会糊成一片灰
            spotColor = Color(0x2B000000),
        )
        .background(fill.copy(alpha = fillAlpha), shape)
        .border(borderWidth, edge, shape)
        .clip(shape)
}

/**
 * 统一开关样式（自绘，不直接用 Material3 的 Switch）。
 *
 * 不用原生 Switch 的两个原因：
 * 1. 关闭态默认 thumb = outline、track = surfaceContainerHighest，而本项目这两个中性色
 *    几乎同色（#E1E6ED / #E3E6EA），圆点会整个融进轨道、看起来只剩一个灰胶囊；
 * 2. 原生 thumb 尺寸随状态变化（选中 24dp / 未选中 16dp），拨动时圆点会「变大变小」，
 *    这里固定 24dp，两态视觉完全一致。
 *
 * 尺寸沿用 M3 的比例：轨道 52×32dp、圆点直径 24dp、圆点四周留 4dp。
 */
@Composable
internal fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackWidth = 52.dp
    val trackHeight = 32.dp
    val thumbSize = 24.dp
    // 圆点在轨道内的上下留白，也是左右两端的起始留白
    val thumbPadding = 4.dp
    val animatedOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - thumbPadding else thumbPadding,
        animationSpec = tween(durationMillis = 180),
        label = "switchThumbOffset",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = tween(durationMillis = 180),
        label = "switchTrackColor",
    )

    Box(
        modifier
            .size(trackWidth, trackHeight)
            .clip(CircleShape)
            .background(if (enabled) trackColor else trackColor.copy(alpha = 0.5f))
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = animatedOffset)
                .size(thumbSize)
                // 一点阴影，让白点在浅灰轨道上更「实」
                .shadow(elevation = 1.dp, shape = CircleShape, clip = false)
                .background(Color.White.copy(alpha = if (enabled) 1f else 0.6f), CircleShape),
        )
    }
}

/** 输入框右侧的「清空」小圆叉：只在有内容时出现，点一下把整段内容清掉。 */
@Composable
private fun ClearFieldButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "清空" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
