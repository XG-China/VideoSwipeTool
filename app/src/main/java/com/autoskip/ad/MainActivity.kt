package com.autoskip.ad

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

private val BrandRed = Color(0xFFE53935)   // 主色:品牌红
private val OkGreen = Color(0xFF2E7D32)    // 状态正常
private val WarnRed = Color(0xFFC62828)    // 状态异常

private val AppColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondaryContainer = Color(0xFFFFE1E0),
    onSecondaryContainer = Color(0xFF3E0A08),
    surface = Color(0xFFFFF8F7),
)

class MainActivity : ComponentActivity() {

    companion object {
        /** 无障碍服务连/断时发这个广播,主界面收到后重新查一次真实状态。 */
        const val ACTION_ACC_STATE = "com.autoskip.ad.ACC_STATE"
    }

    private var accConnected = mutableStateOf(false)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ACC_STATE) {
                accConnected.value = isAccessibilityEnabled(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogBuf.attachFile(this)
        AppSettings.migrateConfig(this)
        // Android 15+ 强制 edge-to-edge:顶部是红色标题栏,状态栏图标用浅色;
        // 底部是浅色内容区,导航栏图标用深色
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                RootScreen(accConnected = accConnected.value)
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_ACC_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        AppSettings.migrateConfig(this)
        val enabled = isAccessibilityEnabled(this)
        accConnected.value = enabled
        // 无障碍服务在后台启动前台服务可能被 Android 12+ 拒绝;
        // 用户打开 App 时(前台)补一次启动,保证保活服务真的起来
        if (enabled) GuardService.start(this)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    /**
     * 判断本服务是否已被系统启用。
     *
     * 注意:系统里存的是**组件扁平串**,存在两种写法,必须都认:
     *   长格式 com.autoskip.ad/com.autoskip.ad.AdSkipAccessibilityService
     *   短格式 com.autoskip.ad/.AdSkipAccessibilityService
     * 统一交给 ComponentName.unflattenFromString 解析后再比对。
     */
    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val target = ComponentName(ctx.packageName, AdSkipAccessibilityService::class.java.name)
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (raw.isNullOrEmpty()) return false
        return raw.split(':').any { entry ->
            val c = ComponentName.unflattenFromString(entry.trim()) ?: return@any false
            c.packageName.equals(target.packageName, ignoreCase = true) &&
                c.className.equals(target.className, ignoreCase = true)
        }
    }
}

// ---------- 主界面 ----------
@Composable
private fun RootScreen(accConnected: Boolean) {
    val ctx = LocalContext.current

    var accOn by remember { mutableStateOf(accConnected) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var stats by remember { mutableStateOf(AppSettings.statsText(ctx)) }
    var enabledTargets by remember { mutableStateOf(AppSettings.targetPackages(ctx)) }

    // Android 13+ 通知权限:GuardService 的保活通知需要它
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(accConnected) { accOn = accConnected }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                logs = LogBuf.snapshot()
                // 悬浮窗权限随时可能在系统设置里被改,轮询才能立刻反映出来
                overlayOn = Settings.canDrawOverlays(ctx)
                stats = AppSettings.statsText(ctx)
                enabledTargets = AppSettings.targetPackages(ctx)
                delay(1000)
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar() },
        containerColor = MaterialTheme.colorScheme.surface
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusCard(
                accOn = accOn,
                overlayOn = overlayOn,
                hasTarget = enabledTargets.isNotEmpty(),
                onOpenAccessibility = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onOpenOverlay = { ctx.startActivity(overlaySettingsIntent(ctx)) }
            )
            UsageCard(onOpenApp = { launchTargetApp(ctx) })
            RuleSettingsCard(
                statsText = stats,
                onStatsReset = {
                    AppSettings.resetStats(ctx)
                    stats = AppSettings.statsText(ctx)
                    LogBuf.add("统计已重置")
                }
            )
            TargetAppsCard(
                enabled = enabledTargets,
                onToggle = { app, on ->
                    AppSettings.setTargetPackage(ctx, app.pkg, on)
                    enabledTargets = AppSettings.targetPackages(ctx)
                    LogBuf.add("目标应用:${app.name} ${if (on) "开启" else "关闭"}")
                }
            )
            TimingCard()
            LogCard(
                logs = logs,
                onClear = {
                    LogBuf.clear()
                    logs = LogBuf.snapshot()
                },
                onExport = { LogExporter.share(ctx) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar() {
    CenterAlignedTopAppBar(
        title = { Text("通用视频上滑工具", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = BrandRed,
            titleContentColor = Color.White
        )
    )
}

// ---------- 目标应用卡 ----------
@Composable
private fun TargetAppsCard(
    enabled: Set<String>,
    onToggle: (AppSettings.AppInfo, Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("目标应用")
            Text(
                "默认一个都不开。勾选需要自动上滑的视频 App;悬浮球面板里也能改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppSettings.KNOWN_APPS.forEach { app ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = app.pkg in enabled,
                        onCheckedChange = { onToggle(app, it) }
                    )
                }
            }
        }
    }
}

// ---------- 状态卡 ----------
@Composable
private fun StatusCard(
    accOn: Boolean,
    overlayOn: Boolean,
    hasTarget: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (accOn && overlayOn && hasTarget) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow("无障碍服务", accOn, if (accOn) null else "开启后才能自动上滑")
            StatusRow("悬浮球", overlayOn, if (overlayOn) null else "缺少「显示在其他应用上层」权限")
            StatusRow("目标应用", hasTarget, if (hasTarget) null else "在下方选择要开启的视频 App")
            if (!accOn) ActionButton("去开启无障碍服务", onOpenAccessibility)
            if (!overlayOn) ActionButton("去开启悬浮球权限", onOpenOverlay)
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, hint: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDot(ok)
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (ok) "正常" else "未就绪",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (ok) OkGreen else WarnRed
            )
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp)
            )
        }
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** 直达本应用的悬浮窗权限页。带 package: 的 uri 才会定位到本 App,否则只到总列表。 */
private fun overlaySettingsIntent(ctx: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:${ctx.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

// ---------- 用法卡 ----------
@Composable
private fun UsageCard(onOpenApp: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("用法")
            Text(
                "命中广告页关键词时自动上滑;剧集页和首页不滑。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "先在下方选择目标应用;点屏幕边缘的小球打开管理面板,长按小球快速开关。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "无网络权限,所有判断都在本地完成。",
                style = MaterialTheme.typography.bodySmall,
                color = OkGreen
            )
            OutlinedButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                Text("打开目标应用")
            }
        }
    }
}

// ---------- 规则设置卡 ----------
@Composable
private fun RuleSettingsCard(statsText: String, onStatsReset: () -> Unit) {
    val ctx = LocalContext.current
    var episodeKeywords by remember { mutableStateOf(AppSettings.episodeKeywords(ctx).joinToString("、")) }
    var homeKeywords by remember { mutableStateOf(AppSettings.homeKeywords(ctx).joinToString("、")) }
    var adKeywords by remember { mutableStateOf(AppSettings.adKeywords(ctx).joinToString("、")) }
    var intervalMs by remember { mutableStateOf(AppSettings.scanIntervalMs(ctx).toInt()) }
    var savedHint by remember { mutableStateOf("") }
    var autoCorrect by remember { mutableStateOf(AppSettings.autoCorrectEpisode(ctx)) }
    var ocrOn by remember { mutableStateOf(AppSettings.ocrEnabled(ctx)) }

    fun saveAll() {
        AppSettings.setEpisodeKeywords(ctx, episodeKeywords)
        AppSettings.setHomeKeywords(ctx, homeKeywords)
        AppSettings.setAdKeywords(ctx, adKeywords)
        AppSettings.setScanIntervalMs(ctx, intervalMs.toLong())
        episodeKeywords = AppSettings.episodeKeywords(ctx).joinToString("、")
        homeKeywords = AppSettings.homeKeywords(ctx).joinToString("、")
        adKeywords = AppSettings.adKeywords(ctx).joinToString("、")
        intervalMs = AppSettings.scanIntervalMs(ctx).toInt()
        savedHint = "已保存"
        LogBuf.add(
            "规则已更新:剧集=${AppSettings.episodeKeywords(ctx).joinToString("/")}, " +
                "首页=${AppSettings.homeKeywords(ctx).joinToString("/")}, " +
                "广告=${AppSettings.adKeywords(ctx).joinToString("/")}, 间隔=${intervalMs}ms"
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("规则设置")

            Text(
                "用关键词判断当前页面:剧集页和首页不滑,广告页上滑;三组关键词都可以自定义。" +
                    "如果什么都没命中,也按广告页上滑。广告里的「返回」是弱信号,有剧集/首页关键词时不生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = episodeKeywords,
                onValueChange = {
                    episodeKeywords = it
                    savedHint = ""
                },
                label = { Text("剧集页关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = homeKeywords,
                onValueChange = {
                    homeKeywords = it
                    savedHint = ""
                },
                label = { Text("首页关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            OutlinedTextField(
                value = adKeywords,
                onValueChange = {
                    adKeywords = it
                    savedHint = ""
                },
                label = { Text("广告页关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("误滑自动回退", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "上滑后从第11集跳到第13集时,自动下滑回第12集,最多回退 3 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCorrect,
                    onCheckedChange = {
                        autoCorrect = it
                        AppSettings.setAutoCorrectEpisode(ctx, it)
                        LogBuf.add("误滑自动回退已${if (it) "开启" else "关闭"}")
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("全屏识字(OCR)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "无障碍树读不到文字时,截屏用本地 OCR 识别广告词。" +
                            "仅 Android 11+ 可用;图片只在内存处理,不上传,会增加耗电。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = ocrOn,
                    onCheckedChange = {
                        ocrOn = it
                        AppSettings.setOcrEnabled(ctx, it)
                        LogBuf.add("全屏识字已${if (it) "开启" else "关闭"}")
                    }
                )
            }

            Text(
                "扫描间隔:${intervalMs} ms",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = intervalMs.toFloat(),
                onValueChange = { intervalMs = it.toInt() },
                valueRange = AppSettings.MIN_SCAN_MS.toFloat()..AppSettings.MAX_SCAN_MS.toFloat(),
                steps = 46
            )

            Button(
                onClick = { saveAll() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
            ) {
                Text("保存规则", fontWeight = FontWeight.SemiBold)
            }
            if (savedHint.isNotEmpty()) {
                Text(
                    savedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = OkGreen
                )
            }

            Text(statsText, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onStatsReset) { Text("重置统计") }
                TextButton(onClick = { LogExporter.share(ctx) }) { Text("导出运行日志") }
            }
        }
    }
}

// ---------- 高级时序 ----------
@Composable
private fun TimingCard() {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var values by remember {
        mutableStateOf(Timing.entries.associateWith { AppSettings.timing(ctx, it) })
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionTitle("高级时序")
                    Text(
                        "扫描、冷却、重试、悬浮球等时间参数,点击展开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandRed
                )
            }

            if (expanded) {
                Text(
                    "修改立即生效;服务连接缓冲期在下一次服务连接时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Timing.entries.forEach { timing ->
                    val value = values[timing] ?: timing.default
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            timing.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (timing.unit == "s") "%.1f s".format(value / 1000.0)
                            else "${value.toInt()} ${timing.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { v ->
                            val newValue = v.toLong()
                            values = values + (timing to newValue)
                            AppSettings.setTiming(ctx, timing, newValue)
                        },
                        onValueChangeFinished = {
                            val v = values[timing] ?: timing.default
                            val text = if (timing.unit == "s") "%.1f s".format(v / 1000.0)
                            else "${v.toInt()} ${timing.unit}"
                            LogBuf.add("[时序] ${timing.label}=$text")
                        },
                        valueRange = timing.min.toFloat()..timing.max.toFloat(),
                        steps = timing.sliderSteps
                    )
                }
                TextButton(onClick = {
                    AppSettings.resetTimings(ctx)
                    values = Timing.entries.associateWith { it.default }
                    LogBuf.add("高级时序已恢复默认")
                }) { Text("恢复默认") }
            }
        }
    }
}

// ---------- 日志卡 ----------
@Composable
private fun LogCard(logs: List<String>, onClear: () -> Unit, onExport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionTitle("运行日志")
                    Text(
                        logs.lastOrNull() ?: "(暂无记录)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (expanded) {
                    OutlinedButton(onClick = onExport) { Text("导出") }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = onClear) { Text("清空") }
                } else {
                    Text("展开", style = MaterialTheme.typography.labelMedium, color = BrandRed)
                }
            }

            if (expanded) {
                Text(
                    "最近 ${LogBuf.maxLines} 条,每秒刷新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        logs.joinToString("\n").ifEmpty { "(暂无记录)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ---------- 小部件 ----------
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .background(if (ok) OkGreen else WarnRed, CircleShape)
    )
}

// ---------- 其他 ----------
private fun launchTargetApp(ctx: Context) {
    val targets = AppSettings.KNOWN_APPS.filter { it.pkg in AppSettings.targetPackages(ctx) }
    if (targets.isEmpty()) {
        Toast.makeText(ctx, "请先在目标应用里选择要开启的 App", Toast.LENGTH_SHORT).show()
        return
    }
    val launch = targets.firstNotNullOfOrNull { app ->
        ctx.packageManager.getLaunchIntentForPackage(app.pkg)
    }
    if (launch != null) {
        runCatching { ctx.startActivity(launch) }
            .onFailure { Toast.makeText(ctx, "打开失败", Toast.LENGTH_SHORT).show() }
    } else {
        Toast.makeText(ctx, "未检测到已开启的目标应用", Toast.LENGTH_SHORT).show()
    }
}
