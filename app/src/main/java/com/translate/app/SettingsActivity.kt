package com.translate.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.translate.app.data.CredentialManager
import com.translate.app.data.settings.AutoTranslate
import com.translate.app.service.AccessibilityEnabler
import com.translate.app.service.BubbleManager
import com.translate.app.service.TranslateAccessibilityService
import com.translate.app.ui.theme.TranslateAppTheme

class SettingsActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 返回后刷新界面
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TranslateAppTheme(dynamicColor = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(
                        onBack = { finish() },
                        onRequestOverlayPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                overlayPermissionLauncher.launch(intent)
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onRequestOverlayPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("translate_settings", Context.MODE_PRIVATE)
    var bubbleEnabled by remember {
        mutableStateOf(prefs.getBoolean("bubble_enabled", false))
    }
    var autoTranslateEnabled by remember { mutableStateOf(AutoTranslate.isEnabled(context)) }
    var accessibilityOn by remember { mutableStateOf(isAccessibilityServiceOn(context)) }
    var autoEnablePermission by remember { mutableStateOf(AccessibilityEnabler.hasPermission(context)) }
    var autoKeepOn by remember { mutableStateOf(AccessibilityEnabler.isAutoEnableOn(context)) }
    var ignoringBattery by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var overlayPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        )
    }

    // 定期检查权限状态
    LaunchedEffect(overlayPermissionGranted) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
            if (granted != overlayPermissionGranted) {
                overlayPermissionGranted = granted
            }
            accessibilityOn = isAccessibilityServiceOn(context)
            autoEnablePermission = AccessibilityEnabler.hasPermission(context)
            ignoringBattery = isIgnoringBatteryOptimizations(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // ⭐ 悬浮球翻译（核心功能）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "使用说明",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "① 在任意应用中选择文本并复制\n" +
                        "② 点击屏幕上的悬浮球\n" +
                        "③ 自动翻译并弹出结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ⭐ 百度翻译 API 配置
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "百度翻译 API 配置",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    var appId by remember {
                        mutableStateOf(CredentialManager.getAppId(context))
                    }
                    var secretKey by remember {
                        mutableStateOf(CredentialManager.getSecretKey(context))
                    }

                    OutlinedTextField(
                        value = appId,
                        onValueChange = { appId = it },
                        label = { Text("APP ID") },
                        placeholder = { Text("输入你的百度翻译 APP ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = secretKey,
                        onValueChange = { secretKey = it },
                        label = { Text("密钥") },
                        placeholder = { Text("输入你的百度翻译密钥") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            CredentialManager.save(context, appId.trim(), secretKey.trim())
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = appId.isNotBlank() && secretKey.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("保存")
                    }

                    if (CredentialManager.isConfigured(context)) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "✅ 已配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 悬浮球开关
            SettingsSwitchItem(
                icon = Icons.Default.TouchApp,
                title = "显示悬浮球",
                summary = when {
                    accessibilityOn -> "由无障碍服务显示：常驻、被清理后自动恢复，无常驻通知"
                    overlayPermissionGranted -> "开启无障碍服务后悬浮球会更稳定（不会随 App 被清理而消失）"
                    else -> "需要悬浮窗权限或无障碍服务"
                },
                checked = bubbleEnabled,
                enabled = overlayPermissionGranted || accessibilityOn,
                onCheckedChange = { enabled ->
                    bubbleEnabled = enabled
                    BubbleManager.setEnabled(context, enabled)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 悬浮窗权限
            SettingsClickItem(
                icon = Icons.Default.Visibility,
                title = "悬浮窗权限",
                summary = if (overlayPermissionGranted) "已授权 ✓" else "未授权 — 点击前往设置开启",
                onClick = {
                    if (!overlayPermissionGranted) {
                        onRequestOverlayPermission()
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 无障碍服务
            SettingsClickItem(
                icon = Icons.Default.Accessibility,
                title = "无障碍服务（推荐）",
                summary = (if (accessibilityOn) "已开启 ✓\n" else "未开启 — ") +
                        "开启后选中文字即可翻译，无需复制（浏览器里选中后点悬浮球，会自动替你点「复制」）\n点击前往系统无障碍设置开启「划词翻译」",
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )

            // 选中英文自动翻译
            SettingsSwitchItem(
                icon = Icons.Default.AutoAwesome,
                title = "选中英文自动翻译",
                summary = if (accessibilityOn)
                    "选中英文后自动弹出翻译，不用复制、不用点悬浮球（输入框内选中不弹出）"
                else
                    "需要先开启上面的无障碍服务",
                checked = autoTranslateEnabled,
                onCheckedChange = { enabled ->
                    autoTranslateEnabled = enabled
                    AutoTranslate.setEnabled(context, enabled)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 自动开启无障碍（需要 ADB 授权一次）
            AutoEnableAccessibilityItem(
                granted = autoEnablePermission,
                keepOn = autoKeepOn,
                command = AccessibilityEnabler.adbCommand(context),
                onCopy = { copyText(context, it) },
                onKeepOnChange = { on ->
                    autoKeepOn = on
                    AccessibilityEnabler.setAutoEnable(context, on)
                    if (on) AccessibilityEnabler.ensureEnabled(context)
                },
                onEnableNow = {
                    AccessibilityEnabler.ensureEnabled(context, restartIfStuck = true, force = true)
                    accessibilityOn = isAccessibilityServiceOn(context)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 防止被系统清理
            SettingsClickItem(
                icon = Icons.Default.BatteryChargingFull,
                title = "关闭电池优化",
                summary = if (ignoringBattery) "已关闭 ✓（系统不会因省电而停止悬浮球）"
                          else "未关闭 — 点击允许后台运行，减少悬浮球 / 无障碍被系统停止",
                onClick = {
                    if (!ignoringBattery) requestIgnoreBatteryOptimizations(context)
                }
            )
            SettingsClickItem(
                icon = Icons.Default.Settings,
                title = "自启动与后台运行",
                summary = "一加 / OPPO：应用信息 → 耗电管理 → 允许后台行为、允许自启动；" +
                        "并在最近任务里下拉卡片「锁定」本应用，避免一键清理时被强行停止",
                onClick = { openAppDetails(context) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 关于
            SettingsClickItem(
                icon = Icons.Default.Info,
                title = "关于",
                summary = "划词翻译 v1.0.0\n基于百度翻译 API | 选中文字 → 点击悬浮球翻译",
                onClick = { }
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsClickItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsClickItem(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 系统设置里是否已开启本应用的无障碍服务 */
private fun isAccessibilityServiceOn(context: Context): Boolean {
    if (TranslateAccessibilityService.isRunning) return true
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val component = android.content.ComponentName(context, TranslateAccessibilityService::class.java)
    return enabled.split(':').any {
        android.content.ComponentName.unflattenFromString(it) == component
    }
}

/**
 * 「自动开启无障碍」：安卓不允许 App 自己打开无障碍服务，
 * 需要用电脑执行一次 ADB 命令授予 WRITE_SECURE_SETTINGS 权限。
 */
@Composable
private fun AutoEnableAccessibilityItem(
    granted: Boolean,
    keepOn: Boolean,
    command: String,
    onCopy: (String) -> Unit,
    onKeepOnChange: (Boolean) -> Unit,
    onEnableNow: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("自动开启无障碍", style = MaterialTheme.typography.titleMedium)
            if (granted) {
                Text(
                    "已授权 ✓ 开机、更新、被系统关闭后 App 再次启动时，都会自动把无障碍服务开回来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "自动保持开启",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = keepOn, onCheckedChange = onKeepOnChange)
                }
                if (!keepOn) {
                    Text(
                        "已关闭：手动关掉无障碍后不会被自动打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                TextButton(onClick = onEnableNow, contentPadding = PaddingValues(0.dp)) {
                    Text("立即开启 / 重新开启")
                }
            } else {
                Text(
                    "安卓不允许 App 自己打开无障碍。用电脑授权一次后即可自动开启（只需做一次，卸载后需重做）：\n" +
                            "1. 手机开启「开发者选项 → USB 调试」并连接电脑\n" +
                            "2. 在电脑上执行下面的命令",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clickable { onCopy(command) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            command,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "复制命令",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    "不想用电脑：也可以在 系统设置 → 无障碍 中手动开启（开关是灰色时：应用信息 → 右上角 ⋮ → 允许受限制的设置）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", text))
    android.widget.Toast.makeText(context, "已复制命令", android.widget.Toast.LENGTH_SHORT).show()
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@android.annotation.SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            openAppDetails(context)
        }
    }
}

private fun openAppDetails(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    } catch (_: Exception) { }
}
