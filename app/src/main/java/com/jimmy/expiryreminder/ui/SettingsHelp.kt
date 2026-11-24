package com.jimmy.expiryreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置项的「?」说明：标题复用条目名，正文写清这个开关/按钮到底做了什么。
 *
 * 文案必须与实现保持一致：改行为时同步改这里，别让说明停留在旧逻辑上。
 */
data class SettingHelp(val title: String, val body: String)

object SettingsHelp {

    val Notify = SettingHelp(
        title = "启用到期通知",
        body = "到期提醒的总开关。\n\n" +
            "· 关闭：立即取消全部已排闹钟，不再推送任何提醒。\n" +
            "· 开启：按当前物品与「推送时刻」重排全部提醒。\n" +
            "· 只有未归档、到期日有效且尚未过期的物品会进排程；每件物品按它在编辑页勾选的「提前提醒天数」逐档提醒，勾了 30/7/1 天就在三个节点各推一条。\n" +
            "· 系统通知权限未授予时，即使这里开着也不会排任何提醒。",
    )

    val NotifyTime = SettingHelp(
        title = "推送时刻",
        body = "所有提醒统一在这一个时刻推送，而不是到期日当天任意时间点。\n\n" +
            "· 触发时间 = (到期日 − 提前天数) 当天的这个时刻。例如到期 6 月 10 日、勾了提前 7 天、时刻 09:00 → 6 月 3 日 09:00 推送。\n" +
            "· 改完立即整包重排，正在等待的闹钟会按新时刻重建。\n" +
            "· 同一天的多条提醒会各自弹一条通知，可能同时堆在通知栏。",
    )

    val AlarmClock = SettingHelp(
        title = "闹钟式提醒",
        body = "决定提醒用哪种系统通道排程。\n\n" +
            "· 开启：走系统的「闹钟」通道（setAlarmClock），抗 Doze 与省电清理最稳；代价是状态栏常驻一个闹钟图标，显示下一次提醒时间。\n" +
            "· 关闭：退化为普通精确闹钟，深度睡眠下可能被系统推迟几分钟到几小时。\n" +
            "· 前提是「精确闹钟权限」已授予；未授予时无论本开关如何，都只能排非精确闹钟。",
    )

    val ExactAlarm = SettingHelp(
        title = "精确闹钟权限",
        body = "本应用不通过应用商店分发，清单里直接声明了精确闹钟权限，安装即授予，正常情况下无需手动操作。\n\n" +
            "· 已授予：提醒可以准点触发。\n" +
            "· 未授予：系统会推迟甚至丢弃提醒，只能排非精确闹钟。\n" +
            "· 部分 ROM 允许用户在系统设置里撤销该权限，撤销时系统会清空全部精确闹钟；重新授权后应用会自动重排。",
    )

    val Battery = SettingHelp(
        title = "忽略电池优化",
        body = "把应用加入系统的电池优化白名单，避免省电策略在后台清掉进程和闹钟。\n\n" +
            "· 已忽略：系统不会因省电限制后台运行，提醒最可靠。\n" +
            "· 未忽略：小米、华为、OPPO、vivo 等机型常会限制后台，导致提醒不弹。\n" +
            "· 这里只申请系统标准白名单，部分 ROM 还需要额外打开自启动。",
    )

    val BackgroundRun = SettingHelp(
        title = "后台运行保障",
        body = "集中检查提醒可靠性的两项系统授权。\n\n" +
            "· 精确闹钟：本应用安装即授予，被 ROM 撤销时需去系统设置重新开启。\n" +
            "· 忽略电池优化：未忽略时，小米/华为/OPPO/vivo 等机型常在后台清掉提醒。\n" +
            "· 点「检查」可查看两项授权状态，并附各机型自启动的设置路径。",
    )

    val Autostart = SettingHelp(
        title = "自启动 / 后台运行",
        body = "国产 ROM 的自启动与后台清理不在标准权限体系内，应用无法自行申请，只能手动设置。\n\n" +
            "· 点「查看」可看到小米/华为/OPPO/vivo 的具体设置路径。\n" +
            "· 在最近任务里「强行停止」本应用，系统会清掉全部闹钟；重新打开一次应用即可恢复。\n" +
            "· 这类限制没放开时，任何应用都无法保证定时提醒。",
    )

    val Scheduled = SettingHelp(
        title = "已排程提醒",
        body = "当前真正交给系统 AlarmManager 的提醒条数，以及最近一条的触发时间。\n\n" +
            "· 每件物品的每个提前档位各占一条，一次最多排 60 条；每触发一条，3 分钟后会自动向前补排，窗口持续滚动。\n" +
            "· 显示 0 条，说明没有待提醒的条目，或被总开关、通知权限挡住了。\n" +
            "· 数字来自上次排程后保存的快照；改数据、改设置、开机、改时区都会触发重排。",
    )

    val Theme = SettingHelp(
        title = "主题",
        body = "三档配色，立即生效并保存。\n\n" +
            "· 跟随系统：跟着手机的深色模式开关实时切换。\n" +
            "· 浅色 / 深色：忽略系统设置，始终使用选定配色。\n" +
            "· 切换后桌面小组件的配色也会跟着刷新。",
    )

    val StatCards = SettingHelp(
        title = "主页统计卡",
        body = "决定主页顶部显示哪几张统计卡，勾选即生效并保存。\n\n" +
            "· 可选：全部 / 正常 / 30天内 / 7天内 / 已过期。\n" +
            "· 卡片顺序固定为「全部 → 正常 → 30天内 → 7天内 → 已过期」，与勾选先后无关。\n" +
            "· 卡片尺寸按 5 张时的紧凑规格固定，增减张数不会让整行变高或变矮。\n" +
            "· 至少要保留一张，取消最后一张会被忽略。",
    )

    val Transfer = SettingHelp(
        title = "跨设备分享",
        body = "手动的一次性传输。\n\n" +
            "· 同一 Wi-Fi 下，发起方生成 4 位配对码，对方比对确认后才开始发送。\n" +
            "· 按「名称 + 到期日」配对，同一件两边都有时以较新的记录为准；只增改，不删除。\n" +
            "· 离开该页面即关闭端口、配对码作废，传完就断，不常驻后台。",
    )

    val Export = SettingHelp(
        title = "导出备份",
        body = "把 data.json 与全部图片打包成一个 .zip，通过系统的「保存到…」选择存放位置。\n\n" +
            "· 包含：物品、分类、空间等数据 + 全部图片文件。\n" +
            "· 不包含：主题、通知时刻等应用设置（这些在导入后保持本机原有配置）。\n" +
            "· 备份是明文 JSON + 原始图片，自行保管时注意隐私。",
    )

    val Import = SettingHelp(
        title = "导入备份",
        body = "从本应用导出的 .zip 恢复数据，选完文件后会问你要「合并」还是「覆盖」。\n\n" +
            "· 合并：相同 id 的记录被备份覆盖，其余保留，适合多设备汇总。\n" +
            "· 覆盖：先清空当前数据再整包写入，只留备份里的内容。\n" +
            "· 只识别本应用导出的备份；文件损坏或格式不对会提示原因，且不会改动现有数据。",
    )

    val LiveUpdate = SettingHelp(
        title = "流体云提醒",
        body = "把 7 天内到期的物品合成一条常驻提醒，并支持系统提升为实时动态 / 状态栏胶囊。\n\n" +
            "· 普通到期通知与流体云是两条链路，互不影响。\n" +
            "· 能否显示为流体云由系统决定：需 Android 16 及以上，且系统允许本应用发布实时活动。\n" +
            "· 状态未提升时，先在系统设置里打开本应用的实时活动开关，再点弹窗里的「重发」。",
    )

    val HideDetail = SettingHelp(
        title = "锁屏隐藏详情",
        body = "开启后，锁屏上的通知只显示「到期提醒」，不出现物品名，解锁后才能看到详情。\n\n" +
            "· 只影响本应用的通知，不影响其他应用。\n" +
            "· 已发布的通知不会立即改写，下一次推送起按新口径显示。\n" +
            "· 流体云常驻提醒同样遵守这个口径。",
    )

    val RefreshWidgets = SettingHelp(
        title = "刷新桌面小组件",
        body = "手动触发一次全部桌面小组件重绘。\n\n" +
            "· 正常情况下数据或主题变化会自动刷新，无需手动操作。\n" +
            "· 若小组件显示的内容明显滞后（如刚改完到期日但小组件没变），点「刷新」即可。",
    )

    val ClearData = SettingHelp(
        title = "清空全部数据",
        body = "删除所有物品及其图片，不可恢复。\n\n" +
            "· 会同时取消全部已排提醒。\n" +
            "· 分类、空间与应用设置不会被重置。\n" +
            "· 建议先「导出备份」再操作。",
    )
}

/**
 * ListItem 的标题行：标题 + 右侧「?」。
 *
 * 「?」用 24dp 触控区包住 17dp 的圆形徽标，行高与原文字行高一致，不会把列表撑高。
 */
@Composable
fun HelpTitle(
    help: SettingHelp,
    modifier: Modifier = Modifier,
    onShow: (SettingHelp) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(help.title, style = MaterialTheme.typography.bodyLarge)
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .clickable { onShow(help) }
                .semantics { contentDescription = "查看「${help.title}」的说明" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "?",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 点「?」后的详细说明弹窗；正文较长时可滚动。 */
@Composable
fun SettingHelpDialog(help: SettingHelp, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(help.title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(help.body, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}
