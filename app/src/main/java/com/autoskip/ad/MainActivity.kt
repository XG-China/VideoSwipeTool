package com.autoskip.ad

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val BrandRed = Color(0xFFE53935)   // 主色:红果红
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
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                RootScreen(accConnected = accConnected.value)
            }
        }
        registerReceiver(receiver, IntentFilter(ACTION_ACC_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        accConnected.value = isAccessibilityEnabled(this)
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
     * 直接拼字符串做 equals 会在短格式下误判为"未开启"(服务其实在跑)。
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

    LaunchedEffect(accConnected) { accOn = accConnected }
    LaunchedEffect(Unit) {
        while (true) {
            logs = LogBuf.snapshot()
            // 悬浮窗权限随时可能在系统设置里被改,轮询才能立刻反映出来
            overlayOn = Settings.canDrawOverlays(ctx)
            delay(1000)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusCard(
                accOn = accOn,
                overlayOn = overlayOn,
                onOpenAccessibility = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onOpenOverlay = { ctx.startActivity(overlaySettingsIntent(ctx)) }
            )
            UsageCard(onOpenApp = { launchHongGuo(ctx) })
            LogCard(logs = logs, onClear = {
                LogBuf.clear()
                logs = LogBuf.snapshot()
            })
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar() {
    CenterAlignedTopAppBar(
        title = { Text("红果免费短剧广告跳过", fontWeight = FontWeight.Bold) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = BrandRed,
            titleContentColor = Color.White
        )
    )
}

// ---------- 状态卡 ----------
/**
 * 状态只给结论,不解释原理。
 * 正常时不显示任何副标题(红点已经说清楚了);出问题时才补一行原因和按钮。
 */
@Composable
private fun StatusCard(
    accOn: Boolean,
    overlayOn: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (accOn && overlayOn) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusRow("无障碍服务", accOn, if (accOn) null else "开启后才能自动上滑")
            StatusRow("悬浮球", overlayOn, if (overlayOn) null else "缺少「显示在其他应用上层」权限")
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
// 目标应用的开关已移入悬浮管理面板,主界面不重复;这里只留规则说明和跳转入口。
@Composable
private fun UsageCard(onOpenApp: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("用法")
            Text(
                "屏幕上找不到「选集」二字时自动上滑跳过。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "点屏幕边缘的小球打开管理面板,总开关在面板里改,拖动小球可换位置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                Text("打开${AppSettings.KNOWN_APPS.first().name}")
            }
        }
    }
}

// ---------- 日志卡 ----------
// 日志是排查用的,不是日常功能:默认只露最新一条,点标题才展开,
// 免得上百行等宽字把上面的状态挤到屏幕外。
@Composable
private fun LogCard(logs: List<String>, onClear: () -> Unit) {
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
private fun launchHongGuo(ctx: Context) {
    val launch = AppSettings.KNOWN_APPS.mapNotNull { app ->
        ctx.packageManager.getLaunchIntentForPackage(app.pkg)
    }.firstOrNull()
    if (launch != null) {
        runCatching { ctx.startActivity(launch) }
            .onFailure { Toast.makeText(ctx, "打开失败", Toast.LENGTH_SHORT).show() }
    } else {
        Toast.makeText(ctx, "未检测到${AppSettings.KNOWN_APPS.first().name}", Toast.LENGTH_SHORT).show()
    }
}
