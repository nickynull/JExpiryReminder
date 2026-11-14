package com.jimmy.expiryreminder

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import com.jimmy.expiryreminder.ui.AppTheme
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
                        settings.useAlarmClock.value,
                    )
                }
            }
        }

    val appScope get() = (application as App).appScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable(stateSaver = ScreenSaver) {
                        mutableStateOf<Screen>(Screen.Home)
                    }
                    // 筛选状态放在导航层：进出编辑/设置页回来仍然是上次的搜索词与分类
                    val homeFilter = rememberSaveable(saver = HomeFilterState.Saver) {
                        HomeFilterState()
                    }
                    // 二级页面按返回键回到主页，不直接退出应用
                    BackHandler(enabled = screen != Screen.Home) {
                        screen = Screen.Home
                    }
                    when (val s = screen) {
                        is Screen.Home -> HomeScreen(
                            filter = homeFilter,
                            onAdd = { defaultCategory ->
                                screen = Screen.Edit(
                                    itemId = null,
                                    defaultCategory = defaultCategory,
                                )
                            },
                            onOcr = { defaultCategory ->
                                screen = Screen.PickImages(defaultCategory = defaultCategory)
                            },
                            onEdit = { id -> screen = Screen.Edit(id) },
                            onSettings = { screen = Screen.Settings },
                            onCategories = { screen = Screen.Categories },
                            onSpaces = { screen = Screen.Spaces },
                            onShare = { ids -> screen = Screen.Transfer(ids) },
                        )
                        is Screen.PickImages -> PickImagesScreen(
                            onBack = { screen = Screen.Home },
                            onConfirm = { uris ->
                                screen = Screen.OcrProgress(
                                    defaultCategory = s.defaultCategory,
                                    uris = uris.map { it.toString() },
                                )
                            },
                        )
                        is Screen.OcrProgress -> OcrProgressScreen(
                            uris = s.uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() },
                            onCancel = {
                                // 回选图页，把已选照片带回去，不白拍
                                screen = Screen.PickImages(
                                    defaultCategory = s.defaultCategory,
                                    initialUris = s.uris,
                                )
                            },
                            onFinish = { draft ->
                                screen = Screen.Edit(
                                    itemId = null,
                                    defaultCategory = s.defaultCategory,
                                    prefill = draft,
                                )
                            },
                        )
                        is Screen.Edit -> EditScreen(
                            itemId = s.itemId,
                            defaultCategory = s.defaultCategory,
                            prefill = s.prefill,
                            onDone = { screen = Screen.Home },
                        )
                        is Screen.Settings -> SettingsScreen(
                            onBack = { screen = Screen.Home },
                            onTransfer = { screen = Screen.Transfer(emptyList()) },
                        )
                        is Screen.Categories -> CategoryScreen(
                            onBack = { screen = Screen.Home },
                        )
                        is Screen.Spaces -> SpaceScreen(
                            onBack = { screen = Screen.Home },
                        )
                        is Screen.Transfer -> TransferScreen(
                            itemIds = s.itemIds,
                            onBack = { screen = Screen.Home },
                        )
                    }
                }
            }
        }
    }
}

sealed interface Screen {
    data object Home : Screen

    /** [defaultCategory] 只在新增（[itemId] 为 null）时生效，来自主页当前的筛选。 */
    data class Edit(
        val itemId: String?,
        val defaultCategory: String? = null,
        /** 识别预填结果；普通新增/编辑为 null。不持久化，仅页面间传递。 */
        val prefill: com.jimmy.expiryreminder.ocr.PrefillDraft? = null,
    ) : Screen
    data object Settings : Screen

    /** 分类管理（独立页面，不再是弹窗）。 */
    data object Categories : Screen

    /** 空间管理（独立页面）。 */
    data object Spaces : Screen

    /** 跨设备传输：从首页多选「分享」进来时带 [itemIds]，从设置进来时为空。 */
    data class Transfer(val itemIds: List<String> = emptyList()) : Screen

    /** 选图页。[initialUris] 用于从识别进度页返回时带回已选照片。 */
    data class PickImages(
        val defaultCategory: String? = null,
        val initialUris: List<String> = emptyList(),
    ) : Screen

    /** 识别进度页。uri 存字符串，便于 rememberSaveable 序列化。 */
    data class OcrProgress(
        val defaultCategory: String? = null,
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
            )
            is Screen.Settings -> mapOf("name" to "settings")
            is Screen.Categories -> mapOf("name" to "categories")
            is Screen.Spaces -> mapOf("name" to "spaces")
            is Screen.Transfer -> mapOf(
                "name" to "transfer",
                "ids" to ArrayList(screen.itemIds),
            )
            is Screen.PickImages -> mapOf(
                "name" to "pick",
                "category" to (screen.defaultCategory ?: ""),
                "uris" to ArrayList(screen.initialUris),
            )
            is Screen.OcrProgress -> mapOf(
                "name" to "ocr",
                "category" to (screen.defaultCategory ?: ""),
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
            "transfer" -> Screen.Transfer(
                (map["ids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            "edit" -> Screen.Edit(
                itemId = (map["itemId"] as? String)?.takeIf { it.isNotEmpty() },
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
            )
            "pick" -> Screen.PickImages(
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                initialUris = (map["uris"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            "ocr" -> Screen.OcrProgress(
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                uris = (map["uris"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            else -> Screen.Home
        }
    },
)
