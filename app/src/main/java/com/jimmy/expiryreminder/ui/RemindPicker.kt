@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.jimmy.expiryreminder.core.REMIND_DAY_MAX
import com.jimmy.expiryreminder.core.REMIND_DAY_OPTIONS
import com.jimmy.expiryreminder.core.normalizeRemindDays
import com.jimmy.expiryreminder.core.remindDayLabel
import com.jimmy.expiryreminder.core.remindDaysSummary

/**
 * 选中态配色：容器取应用图标的底色（琥珀 #FFC107），文字/图标用深棕保证对比度。
 */
@Composable
private fun selectedChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color(0xFFFFC107),
    selectedLabelColor = Color(0xFF3D2B00),
    selectedLeadingIconColor = Color(0xFF3D2B00),
    selectedTrailingIconColor = Color(0xFF3D2B00),
)

/** 自定义入口的固定文案；参与最长档位测量，保证它和其他胶囊同宽。 */
private const val CUSTOM_LABEL = "自定义"

/**
 * 提前提醒档位的 chip 选择器（编辑页与分类默认值共用一套）。
 *
 * 预设档位之外，用户自填的天数会作为额外档位并入，整体按由远到近排，「当天」固定收尾。
 * 所有胶囊统一宽度：按最长档位文案（含「自定义」）定标签盒宽，文字恒居中，
 * 选中态只用琥珀底色 + 深棕字表示（不画 ×，不占额外宽度），勾选/取消瞬间胶囊不变宽变窄。
 */
@Composable
fun RemindDayChips(
    selected: List<Int>,
    onToggle: (Int) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
    /** chip 高度：默认 30dp（设置弹窗），编辑页传 26dp 更紧凑。 */
    chipHeight: androidx.compose.ui.unit.Dp = 30.dp,
    /** 紧凑文案：档位显示「60天」而不是「60 天前」，chip 变窄能少排一行；语义由外层「提前提醒」标签补足。 */
    compactLabel: Boolean = false,
) {
    val customDays = selected.filter { it !in REMIND_DAY_OPTIONS }.sortedDescending()
    val chips = (REMIND_DAY_OPTIONS.filter { it > 0 } + customDays).sortedDescending() +
        REMIND_DAY_OPTIONS.filter { it == 0 }
    fun chipText(day: Int) = if (compactLabel) {
        if (day == 0) "当天" else "${day}天"
    } else {
        remindDayLabel(day)
    }

    // 统一标签盒宽度：最长档位文案（含「自定义」），文字居中，选中态只换底色不画 ×。
    // M3 chip 的标签默认样式是 labelLarge，按它量才准。
    val labelStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelBoxWidth = remember(selected, compactLabel, labelStyle, textMeasurer, density) {
        with(density) {
            (chips.map(::chipText) + CUSTOM_LABEL)
                .maxOf { textMeasurer.measure(it, style = labelStyle).size.width }
                .toDp()
        }
    }
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (day in chips) {
            val on = day in selected
            FilterChip(
                selected = on,
                onClick = { onToggle(day) },
                label = {
                    Box(Modifier.width(labelBoxWidth), contentAlignment = Alignment.Center) {
                        Text(chipText(day), maxLines = 1)
                    }
                },
                colors = selectedChipColors(),
                modifier = Modifier.height(chipHeight),
            )
        }
        // 自定义入口：录入 1~365 天，确认后作为一枚档位加入（标签盒与其他档位统一）
        FilterChip(
            selected = false,
            onClick = onCustom,
            label = {
                Box(Modifier.width(labelBoxWidth), contentAlignment = Alignment.Center) {
                    Text(CUSTOM_LABEL, maxLines = 1)
                }
            },
            colors = selectedChipColors(),
            modifier = Modifier.height(chipHeight),
        )
    }
}

/**
 * 分类「默认提前提醒天数」的选择弹窗：改的是草稿，点保存才写回。
 *
 * 空列表是合法结果（表示这个分类新增物品时不预选档位），所以保存按钮永远可点。
 */
@Composable
fun RemindDayPickerDialog(
    title: String,
    subtitle: String,
    initial: List<Int>,
    onConfirm: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var days by remember(initial) { mutableStateOf(initial) }
    var showCustom by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                RemindDayChips(
                    selected = days,
                    onToggle = { day -> days = if (day in days) days - day else days + day },
                    onCustom = { showCustom = true },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        days.isEmpty() -> "未选择：新增该分类物品时不预选任何提醒档位"
                        else -> "将预选：${remindDaysSummary(days) ?: ""}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(days) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showCustom) {
        CustomRemindDayDialog(
            remindDays = days,
            onConfirm = { day ->
                days = normalizeRemindDays(days + day)
                showCustom = false
            },
            onDismiss = { showCustom = false },
        )
    }
}

/**
 * 自定义提前天数弹窗：只收 1~[REMIND_DAY_MAX] 的正整数，越界与重复档位在弹窗内就地拦下，
 * 免得加进档位后才发现「填了也不生效」。
 */
@Composable
fun CustomRemindDayDialog(
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
