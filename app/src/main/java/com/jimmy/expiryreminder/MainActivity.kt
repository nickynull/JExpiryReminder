package com.jimmy.expiryreminder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import com.jimmy.expiryreminder.ui.AppTheme
import com.jimmy.expiryreminder.ui.ArchiveScreen
import com.jimmy.expiryreminder.ui.GestureExclusionStrip
import com.jimmy.expiryreminder.ui.backDragGesture
import com.jimmy.expiryreminder.ui.CategoryScreen
import com.jimmy.expiryreminder.ui.EditScreen
import com.jimmy.expiryreminder.ui.HomeFilterState
import com.jimmy.expiryreminder.ui.HomeScreen
import com.jimmy.expiryreminder.ui.OcrProgressScreen
import com.jimmy.expiryreminder.ui.PickImagesScreen
import com.jimmy.expiryreminder.ui.SettingsScreen
import com.jimmy.expiryreminder.ui.SpaceScreen
import com.jimmy.expiryreminder.ui.TransferScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val appContext = applicationContext
            if (granted) {
                appScope.launch {
                    val store = ExpiryStore.get()
                    val settings = SettingsStore.get()
                    ReminderService.syncScheduled(
                        appContext, store, settings.notifyEnabled.value, settings.notifyTime.value,
                        settings.useAlarmClock.value, settings.liveUpdateEnabled.value,
                    )
                }
            }
        }

    val appScope get() = (application as App).appScope

    companion object {
        /** 桌面小组件列表项点击时带入的物品 id。 */
        const val EXTRA_OPEN_ITEM_ID = "widget_item_id"

        /** 桌面小组件右上角「+」：直接打开新增页。 */
        const val EXTRA_OPEN_ADD = "widget_open_add"

        /** 桌面小组件「+」左侧相机键：直接打开与主页相机按钮相同的选图（拍照）页。 */
        const val EXTRA_OPEN_CAMERA = "widget_open_camera"

        /**
         * 长按桌面图标的快捷方式带入的标识（见 res/xml/shortcuts.xml）。
         * 快捷方式 XML 的 extra 只能是字符串，所以不能复用上面的 Boolean extra。
         */
        const val EXTRA_SHORTCUT = "shortcut"

        /** 快捷方式：直接进新增页。 */
        const val SHORTCUT_ADD = "add"

        /** 快捷方式：直接进选图（拍照）页。 */
        const val SHORTCUT_CAMERA = "camera"
    }

    /**
     * 应用已在运行时再次收到启动 Intent（长按桌面快捷方式、或小组件按钮）走这里。
     * 用 State 而非直接设置 screen：screen 是 Compose 内部 rememberSaveable 状态，
     * 由 setContent 里的 LaunchedEffect 消费后再清空，等价于「一次性跳转指令」。
     */
    private val pendingNav = mutableStateOf<Intent?>(null)

    /** 小组件行点击带来的「定位物品」id：进主页后滚动到该行并短暂高亮，消费后由页面层清空。 */
    val pendingLocate = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 覆盖 Activity.intent，避免后续读取到上一次的旧 extra
        setIntent(intent)
        pendingNav.value = intent
    }

    /** 把启动 Intent 翻译成目标页面；普通点图标启动返回 null（停在主页）。 */
    private fun resolveScreen(intent: Intent?): Screen? {
        if (intent == null) return null
        // 桌面小组件行点击：不再进编辑页，改为回主页定位该物品
        // （由 setContent 的 LaunchedEffect 读 EXTRA_OPEN_ITEM_ID 处理，不走这里）
        if (intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) return Screen.Edit(itemId = null)
        if (intent.getBooleanExtra(EXTRA_OPEN_CAMERA, false)) return Screen.PickImages()
        // 长按桌面图标的快捷方式
        when (intent.getStringExtra(EXTRA_SHORTCUT)) {
            SHORTCUT_ADD -> return Screen.Edit(itemId = null)
            SHORTCUT_CAMERA -> return Screen.PickImages()
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动带定位 extra（小组件行点击首次拉起应用）：种子进 pendingLocate，
        // 首屏 Home 组合后消费；热启动（onNewIntent）在下面的 LaunchedEffect 里处理
        pendingLocate.value = intent?.getStringExtra(EXTRA_OPEN_ITEM_ID)?.takeIf { it.isNotEmpty() }
        // 声明 edge-to-edge，并把系统栏图标外观跟随应用主题：
        // 浅色主题下用深色图标（默认白色图标在白底头部上不可见）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val settings = SettingsStore.get()
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()

            // 系统栏图标深浅跟随应用主题（system 跟系统，light 用深色图标，dark 用浅色图标）
            val darkTheme = when (themeMode) {
                com.jimmy.expiryreminder.core.SettingsStore.ThemeMode.dark -> true
                com.jimmy.expiryreminder.core.SettingsStore.ThemeMode.light -> false
                com.jimmy.expiryreminder.core.SettingsStore.ThemeMode.system ->
                    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            androidx.compose.runtime.SideEffect {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            // 首次启动请求通知权限
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // 前台兜底：补发今天推送时刻已过、但闹钟被系统吞掉的那几条
                appScope.launch {
                    val store = ExpiryStore.get()
                    store.load(applicationContext)
                    ReminderService.runForegroundCheck(
                        applicationContext, store,
                        settings.notifyEnabled.value, settings.notifyTime.value,
                    )
                }
            }

            AppTheme(themeMode.name) {
                Surface {
                    var screen by rememberSaveable(stateSaver = ScreenSaver) {
                        // 冷启动首屏由启动 Intent 决定：小组件按钮、或长按图标的快捷方式。
                        // 应用已运行时的二次触发走 onNewIntent → pendingNav（见下）。
                        mutableStateOf(resolveScreen(intent) ?: Screen.Home)
                    }
                    // 应用已在前台时点快捷方式/小组件按钮不会重建 Activity，
                    // 系统走 onNewIntent，这里消费掉这条「一次性跳转指令」再清空。
                    val navIntent by pendingNav
                    LaunchedEffect(navIntent) {
                        val intent = navIntent ?: return@LaunchedEffect
                        // 小组件行点击：带物品 id → 回主页定位（不进编辑页）
                        val locate = intent.getStringExtra(EXTRA_OPEN_ITEM_ID)?.takeIf { it.isNotEmpty() }
                        if (locate != null) {
                            pendingLocate.value = locate
                            screen = Screen.Home
                            pendingNav.value = null
                            return@LaunchedEffect
                        }
                        val target = resolveScreen(intent) ?: return@LaunchedEffect
                        screen = target
                        pendingNav.value = null
                    }
                    // 筛选状态放在导航层：进出编辑/设置页回来仍然是上次的搜索词与分类
                    val homeFilter = rememberSaveable(saver = HomeFilterState.Saver) {
                        HomeFilterState()
                    }
                    // 归档页是独立页面（ArchiveScreen），筛选状态同样独立一份，
                    // 与主页互不干扰（主页筛到「药品」时进归档仍看全部归档物品）
                    val archiveFilter = rememberSaveable(saver = HomeFilterState.Saver) {
                        HomeFilterState()
                    }
                    // 主页离开组合（跳编辑页等）时暂存它内部所有 rememberSaveable 状态，
                    // 返回时原样恢复——否则列表滚动位置会丢，回到顶部。
                    // 只包主页：编辑页等二级页每次进入都应是干净的，不恢复上次草稿。
                    val homeStateHolder = rememberSaveableStateHolder()
                    // 返回只有一种转场（与左上角按钮一致）：整页右滑出 + 新页淡入。
                    var exitKind by remember { mutableStateOf(ExitKind.Enter) }

                    // —— 跟手返回（左边缘拖拽，参考酷安）——
                    // 预测性返回已在 Manifest 里彻底关闭（enableOnBackInvokedCallback=false），
                    // 系统不再下发 onBackProgressed，也就没有任何系统进度可用；
                    // 酷安的做法也是把左边缘从系统手势里排除后自己处理触摸。
                    // 这里同样：BackDragStrip 申请系统手势排除并驱动进度，页面 1:1 跟手
                    // 位移，底层渲染主页 + 随进度减弱的压暗层，松手按位移/速度提交或回弹。
                    var backDragActive by remember { mutableStateOf(false) }
                    var backDragProgress by remember { mutableFloatStateOf(0f) }
                    // 二级页刚切入时 AnimatedContent 的出场转场还在跑，旧主页仍在组合里；
                    // 此时立刻拖拽会让底层主页与它同时组合（SaveableStateProvider 同 key
                    // 冲突），等转场落定再启用手势条。
                    var navSettled by remember { mutableStateOf(false) }
                    LaunchedEffect(screen) {
                        navSettled = false
                        kotlinx.coroutines.delay(300)
                        navSettled = true
                    }
                    var containerWidthPx by remember { mutableFloatStateOf(0f) }
                    // 左边缘手势区宽度（px），与 GestureExclusionStrip 的 32dp 对齐
                    val stripWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                        32.dp.toPx()
                    }
                    val navScope = rememberCoroutineScope()

                    // 二级页栈：归档页也是「主页」的一种，从它进编辑/设置后返回必须回归档页。
                    // 只存内存，进程重建后从 screen 恢复单页、栈为空则回主页（可接受）。
                    val backStack = remember { androidx.compose.runtime.mutableStateListOf<Screen>() }

                    fun navigate(target: Screen) {
                        if (target == screen) return
                        if (screen != Screen.Home) backStack.add(screen)
                        exitKind = ExitKind.Enter
                        screen = target
                    }

                    /**
                     * 用 target 顶掉当前页（当前页不留返回栈）。
                     * OCR 流程的选图页/识别页是一次性过路页：页间跳转必须走这里，
                     * 否则它们会留在返回历史里，返回键 pop 回识别页会重新识别一遍，
                     * 表现为「按返回永远出不去、反复识别」。
                     */
                    fun navigateReplace(target: Screen) {
                        if (target == screen) return
                        exitKind = ExitKind.Enter
                        screen = target
                    }

                    /** 返回上一层：栈空即回主页。收起键盘等由各页自己处理。 */
                    fun navBack(kind: ExitKind) {
                        val target = backStack.removeLastOrNull() ?: Screen.Home
                        if (target == screen) return
                        exitKind = kind
                        screen = target
                    }

                    /** 保存/删除完成：清栈直接回主页，别停在归档/设置这些中间层。 */
                    fun navHome(kind: ExitKind) {
                        if (screen == Screen.Home) return
                        backStack.clear()
                        exitKind = kind
                        screen = Screen.Home
                    }

                    fun goBack() = navBack(ExitKind.ByButton)

                    fun finishBackDrag(commit: Boolean) {
                        navScope.launch {
                            if (commit) {
                                // 补齐到全宽后提交：新旧页瞬时互换（ByDrag snap 转场），
                                // 主页从底层状态恢复，视觉无缝
                                if (backDragProgress < 1f) {
                                    animate(
                                        backDragProgress, 1f,
                                        animationSpec = tween(160, easing = FastOutSlowInEasing),
                                    ) { v, _ -> backDragProgress = v }
                                }
                                exitKind = ExitKind.ByDrag
                                // 提交后落回上一层（可能是主页，也可能是归档页）
                                screen = backStack.removeLastOrNull() ?: Screen.Home
                                backDragProgress = 0f
                                backDragActive = false
                            } else {
                                // 回弹：页面滑回原位
                                animate(
                                    backDragProgress, 0f,
                                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                                ) { v, _ -> backDragProgress = v }
                                backDragActive = false
                            }
                        }
                    }

                    val homeContent: @Composable () -> Unit = {
                        HomeScreen(
                            filter = homeFilter,
                            locateItemId = pendingLocate.value,
                            onLocateDone = { pendingLocate.value = null },
                            onAdd = { defaultCategory, defaultSpace ->
                                navigate(Screen.Edit(
                                    itemId = null,
                                    defaultCategory = defaultCategory,
                                    defaultSpace = defaultSpace,
                                ))
                            },
                            onOcr = { defaultCategory, defaultSpace ->
                                navigate(Screen.PickImages(
                                    defaultCategory = defaultCategory,
                                    defaultSpace = defaultSpace,
                                ))
                            },
                            onEdit = { id -> navigate(Screen.Edit(id)) },
                            onSettings = { navigate(Screen.Settings) },
                            onCategories = { navigate(Screen.Categories) },
                            onSpaces = { navigate(Screen.Spaces) },
                            onArchive = { navigate(Screen.Archive) },
                            onShare = { ids -> navigate(Screen.Transfer(ids)) },
                        )
                    }

                    // 归档页：独立页面，只接管自己的筛选状态与返回入口
                    val archiveContent: @Composable () -> Unit = {
                        ArchiveScreen(
                            filter = archiveFilter,
                            onBack = { goBack() },
                            onEdit = { id -> navigate(Screen.Edit(id)) },
                        )
                    }

                    // 单页内容：AnimatedContent 与拖拽返回的底层共用同一套分支——
                    // 返回目标既可能是主页，也可能是归档页
                    val screenContent: @Composable (Screen) -> Unit = { target ->
                    when (val s = target) {
                        is Screen.Home -> homeStateHolder.SaveableStateProvider(key = "home") {
                            homeContent()
                        }
                        is Screen.PickImages -> PickImagesScreen(
                            onBack = { goBack() },
                            // 从识别页返回时带回归前已选照片（initialUris 存字符串，转回 Uri）
                            initialUris = s.initialUris.mapNotNull {
                                runCatching { Uri.parse(it) }.getOrNull()
                            },
                            onConfirm = { uris ->
                                // 选图页（带已选照片）必须留在栈里，识别页返回才有落脚页。
                                // 注意：从主页进的选图页不在栈里（navigate 不压主页锚点），
                                // 之前只做「栈顶替换」会导致识别中按返回直接跳回主页。
                                val withUris = s.copy(initialUris = uris.map { it.toString() })
                                if (backStack.lastOrNull() is Screen.PickImages) {
                                    backStack[backStack.lastIndex] = withUris
                                } else {
                                    backStack.add(withUris)
                                }
                                navigateReplace(Screen.OcrProgress(
                                    defaultCategory = s.defaultCategory,
                                    defaultSpace = s.defaultSpace,
                                    uris = uris.map { it.toString() },
                                ))
                            },
                        )
                        is Screen.OcrProgress -> OcrProgressScreen(
                            uris = s.uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() },
                            onCancel = {
                                // 识别中返回：弹栈回选图页（栈顶已带已选照片），不再压新页
                                goBack()
                            },
                            onFinish = { draft ->
                                // 识别完成：把选图页/识别页从返回历史里全部摘掉，
                                // 编辑页保存或返回都直接回主页，不会再掉回识别页重新识别
                                backStack.removeAll { it is Screen.PickImages || it is Screen.OcrProgress }
                                navigateReplace(Screen.Edit(
                                    itemId = null,
                                    defaultCategory = s.defaultCategory,
                                    defaultSpace = s.defaultSpace,
                                    prefill = draft,
                                ))
                            },
                        )
                        // 按 itemId 分组：从「新增」切到「编辑某条已有物品」时仍停在同一个 when 分支、
                        // 同一个调用点，不给 key 的话 Compose 会复用上页的组合状态，
                        // 编辑页里的 remember 字段初值不会按新 existing 重新取，会显示上一页的草稿。
                        is Screen.Edit -> key(s.itemId ?: "new") {
                            EditScreen(
                                itemId = s.itemId,
                                defaultCategory = s.defaultCategory,
                                defaultSpace = s.defaultSpace,
                                prefill = s.prefill,
                                // 新增时点选「相似物品」里的某条，直接切到那一条的编辑页
                                onEditExisting = { id -> navigate(Screen.Edit(id)) },
                                onDone = { goBack() },
                                // 保存/删除成功：淡入淡出回到来源页（主页或归档页），
                                // 从归档点进去编辑的物品保存后仍回归档，不会莫名跳回主页
                                onSaved = { navBack(ExitKind.AfterSave) },
                            )
                        }
                        is Screen.Settings -> SettingsScreen(
                            onBack = { goBack() },
                            onTransfer = { navigate(Screen.Transfer(emptyList())) },
                        )
                        is Screen.Categories -> CategoryScreen(
                            onBack = { goBack() },
                        )
                        is Screen.Spaces -> SpaceScreen(
                            onBack = { goBack() },
                        )
                        is Screen.Archive -> homeStateHolder.SaveableStateProvider(key = "archive") {
                            archiveContent()
                        }
                        is Screen.Transfer -> TransferScreen(
                            itemIds = s.itemIds,
                            onBack = { goBack() },
                        )
                    }
                    }

                    // 返回统一走按钮同款转场：二级页拦截返回（手势/返回键），
                    // 主页不拦截，系统返回直接回桌面并保留平台动画。
                    // 拖拽进行中禁用 BackHandler，避免系统返回与拖拽提交打架。
                    BackHandler(enabled = screen != Screen.Home && !backDragActive) {
                        goBack()
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { containerWidthPx = it.size.width.toFloat() }
                            // 左边缘拖拽返回：挂在根容器上检测（见 GestureExclusionStrip 注释，
                            // 之前放在覆盖 Box 上会吞掉下方控件的点击），按下点在左 32dp 内才生效
                            .backDragGesture(
                                enabled = screen != Screen.Home && navSettled && containerWidthPx > 0f,
                                stripWidthPx = stripWidthPx,
                                screenWidthPx = containerWidthPx,
                                onDrag = { pr ->
                                    backDragActive = true
                                    backDragProgress = pr
                                },
                                onFinish = { commit -> finishBackDrag(commit) },
                            ),
                    ) {
                        // 拖拽返回时，底层渲染「返回目标」（栈顶）+ 随进度减弱的压暗层。
                        // 从归档页进的编辑页，拖拽露出的应是归档列表而不是主页。
                        if (backDragActive && screen != Screen.Home) {
                            Box(Modifier.fillMaxSize()) {
                                screenContent(backStack.lastOrNull() ?: Screen.Home)
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            Color.Black.copy(alpha = 0.5f * (1f - backDragProgress)),
                                        ),
                                )
                            }
                        }
                        // 流体动效：进二级页从右侧轻推进入；返回（按钮/手势）整页右滑出；保存淡入淡出。
                        // 跟手拖拽期间整层随进度平移（graphicsLayer 只重绘不重组）。
                        AnimatedContent(
                            targetState = screen,
                            modifier = Modifier.graphicsLayer {
                                translationX = backDragProgress * containerWidthPx
                            },
                            transitionSpec = {
                                // 方向只认 exitKind：返回目标不一定是主页（也可能是归档页），
                                // 不能再拿「目标是不是 Home」判断进出
                                when (exitKind) {
                                    ExitKind.Enter ->
                                        // 进二级页：保留短距离横向层级感
                                        (slideInHorizontally(tween(220)) { it / 10 } +
                                            fadeIn(tween(180))) togetherWith
                                            (slideOutHorizontally(tween(220)) { -it / 16 } +
                                                fadeOut(tween(140)))
                                    ExitKind.ByButton ->
                                        // 返回（按钮或系统手势）：整页向右滑出、新页淡入
                                        fadeIn(tween(200)) togetherWith
                                            (slideOutHorizontally(tween(240)) { it } +
                                                fadeOut(tween(220)))
                                    ExitKind.ByDrag ->
                                        // 跟手返回提交：页面已拖出屏外，新旧页瞬时互换
                                        fadeIn(snap()) togetherWith slideOutHorizontally(snap())
                                    ExitKind.AfterSave ->
                                        // 保存/删除完成：平台式淡入淡出，不模拟返回手势
                                        fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                }
                            },
                            label = "screen",
                        ) { target ->
                            screenContent(target)
                        }
                        // 左边缘系统手势排除矩形：只申请排除、不处理触摸（对点击透明），
                        // 拖拽检测由根容器的 backDragGesture 驱动
                        GestureExclusionStrip(Modifier.align(Alignment.CenterStart))
                    }
                }
            }
        }
    }
}

/** 离开二级页的转场方式：返回走横向滑出，跟手拖拽提交走瞬时互换，保存/删除走淡入淡出。 */
private enum class ExitKind { Enter, ByButton, ByDrag, AfterSave }

sealed interface Screen {
    data object Home : Screen

    /** [defaultCategory] / [defaultSpace] 只在新增（[itemId] 为 null）时生效，来自主页当前的筛选。 */
    data class Edit(
        val itemId: String?,
        val defaultCategory: String? = null,
        /** 主页筛选的空间；「全部空间」「未分配」时为空，新增物品默认不归属空间。 */
        val defaultSpace: String? = null,
        /** 识别预填结果；普通新增/编辑为 null。不持久化，仅页面间传递。 */
        val prefill: com.jimmy.expiryreminder.ocr.PrefillDraft? = null,
    ) : Screen
    data object Settings : Screen

    /** 分类管理（独立页面，不再是弹窗）。 */
    data object Categories : Screen

    /** 空间管理（独立页面）。 */
    data object Spaces : Screen

    /** 归档页：已归档物品列表，可调回或删除。 */
    data object Archive : Screen

    /** 跨设备传输：从首页多选「分享」进来时带 [itemIds]，从设置进来时为空。 */
    data class Transfer(val itemIds: List<String> = emptyList()) : Screen

    /** 选图页。[initialUris] 用于从识别进度页返回时带回已选照片。 */
    data class PickImages(
        val defaultCategory: String? = null,
        val defaultSpace: String? = null,
        val initialUris: List<String> = emptyList(),
    ) : Screen

    /** 识别进度页。uri 存字符串，便于 rememberSaveable 序列化。 */
    data class OcrProgress(
        val defaultCategory: String? = null,
        val defaultSpace: String? = null,
        val uris: List<String> = emptyList(),
    ) : Screen
}

/**
 * Screen 不是 Bundle 可保存类型（自定义 sealed 类），直接 rememberSaveable 会在
 * 首帧组合时抛 IllegalArgumentException 导致启动即闪退，这里换成手动序列化。
 */
private val ScreenSaver = mapSaver(
    save = { screen ->
        when (screen) {
            is Screen.Edit -> mapOf(
                "name" to "edit",
                "itemId" to (screen.itemId ?: ""),
                "category" to (screen.defaultCategory ?: ""),
                "space" to (screen.defaultSpace ?: ""),
            )
            is Screen.Settings -> mapOf("name" to "settings")
            is Screen.Categories -> mapOf("name" to "categories")
            is Screen.Spaces -> mapOf("name" to "spaces")
            is Screen.Archive -> mapOf("name" to "archive")
            is Screen.Transfer -> mapOf(
                "name" to "transfer",
                "ids" to ArrayList(screen.itemIds),
            )
            is Screen.PickImages -> mapOf(
                "name" to "pick",
                "category" to (screen.defaultCategory ?: ""),
                "space" to (screen.defaultSpace ?: ""),
                "uris" to ArrayList(screen.initialUris),
            )
            is Screen.OcrProgress -> mapOf(
                "name" to "ocr",
                "category" to (screen.defaultCategory ?: ""),
                "space" to (screen.defaultSpace ?: ""),
                "uris" to ArrayList(screen.uris),
            )
            else -> mapOf("name" to "home")
        }
    },
    restore = { map ->
        when (map["name"]) {
            "settings" -> Screen.Settings
            "categories" -> Screen.Categories
            "spaces" -> Screen.Spaces
            "archive" -> Screen.Archive
            "transfer" -> Screen.Transfer(
                (map["ids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            "edit" -> Screen.Edit(
                itemId = (map["itemId"] as? String)?.takeIf { it.isNotEmpty() },
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                defaultSpace = (map["space"] as? String)?.takeIf { it.isNotEmpty() },
            )
            "pick" -> Screen.PickImages(
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                defaultSpace = (map["space"] as? String)?.takeIf { it.isNotEmpty() },
                initialUris = (map["uris"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            "ocr" -> Screen.OcrProgress(
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                defaultSpace = (map["space"] as? String)?.takeIf { it.isNotEmpty() },
                uris = (map["uris"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            else -> Screen.Home
        }
    },
)
