package com.xixin.vmq.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.xixin.vmq.keepalive.KeepAliveGuide
import com.xixin.vmq.keepalive.ListenerStatus
import com.xixin.vmq.keepalive.Watchdog
import com.xixin.vmq.network.ConnDiagnostics
import com.xixin.vmq.network.KtorClient
import com.xixin.vmq.network.PingResult
import com.xixin.vmq.store.AppStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by AppStore.logs.collectAsState()
    val endpoint by AppStore.apiEndpoint.collectAsState()
    val secretKey by AppStore.secretKey.collectAsState()

    val hasPermission by AppStore.hasListenerPermission.collectAsState()
    val hasNotification by AppStore.hasNotificationPermission.collectAsState()

    // 控制底部设置面板的显示状态
    var showSettingsSheet by remember { mutableStateOf(false) }
    // 控制保活引导面板
    var showKeepAliveSheet by remember { mutableStateOf(false) }
    // 页面切换：0=监控 1=账本
    var page by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(
                    when (page) {
                        0 -> "V免签监控"
                        1 -> "收款账本"
                        else -> "订单对账"
                    },
                    fontWeight = FontWeight.Bold
                ) },
                actions = {
                    if (page == 0) {
                        // 保活引导：本应用最容易踩的坑，给一个显眼入口
                        IconButton(onClick = { showKeepAliveSheet = true }) {
                            Icon(Icons.Default.BatterySaver, contentDescription = "后台保活设置")
                        }
                    }
                    // 低频操作入口：放在右上角，不占空间
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "通讯设置")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == 0,
                    onClick = { page = 0 },
                    icon = { Icon(Icons.Default.Radar, contentDescription = null) },
                    label = { Text("监控", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = page == 1,
                    onClick = { page = 1 },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                    label = { Text("账本", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = page == 2,
                    onClick = { page = 2 },
                    icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) },
                    label = { Text("订单", fontSize = 11.sp) }
                )
            }
        }
    ) { paddingValues ->
        if (page == 1) {
            Box(Modifier.padding(paddingValues)) {
                LedgerScreen()
            }
            return@Scaffold
        }
        if (page == 2) {
            Box(Modifier.padding(paddingValues)) {
                OrdersScreen()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // 1. 核心权限状态卡片（醒目）
            PermissionCard(hasPermission) {
                KeepAliveGuide.openListenerSettings(context)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 保活状态速览（点开是完整引导）
            KeepAliveSummaryCard(hasListener = hasPermission, onClick = { showKeepAliveSheet = true })

            Spacer(modifier = Modifier.height(16.dp))

            // 3. 高频测试区（直接在主页，方便点击）
            QuickTestCard()

            Spacer(modifier = Modifier.height(16.dp))

            // 4. 运行日志区（占据剩余全部屏幕，互不干扰）
            LogTerminalCard(
                logs = logs,
                onCopy = {
                    val logText = logs.joinToString("\n")
                    clipboardManager.setText(AnnotatedString(logText))
                    Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                },
                onClear = { AppStore.clearLogs() },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 隐藏的低频设置面板 (BottomSheet)
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SettingsPanel(
                endpoint = endpoint,
                secretKey = secretKey,
                onSave = { showSettingsSheet = false }
            )
        }
    }

    // 保活引导面板
    if (showKeepAliveSheet) {
        ModalBottomSheet(
            onDismissRequest = { showKeepAliveSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            KeepAlivePanel(
                listenerGranted = hasPermission,
                notificationGranted = hasNotification,
                onDismiss = { showKeepAliveSheet = false }
            )
        }
    }
}

@Composable
fun QuickTestCard() {
    var mockPrice by remember { mutableStateOf("0.01") }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("快捷测试", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = mockPrice,
                    onValueChange = { mockPrice = it },
                    label = { Text("金额") },
                    modifier = Modifier.weight(1f).height(60.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { KtorClient.pushPayment("alipay", mockPrice.trim()) },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                ) { Text("支付宝", fontSize = 13.sp) }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = { KtorClient.pushPayment("wechat", mockPrice.trim()) },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                ) { Text("微信", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
fun SettingsPanel(endpoint: String, secretKey: String, onSave: () -> Unit) {
    val context = LocalContext.current
    var localEndpoint by remember { mutableStateOf(endpoint) }
    var localSecret by remember { mutableStateOf(secretKey) }
    val scope = rememberCoroutineScope()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<PingResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) // 适配底部小白条
    ) {
        Text("通讯设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = localEndpoint,
            onValueChange = { localEndpoint = it },
            label = { Text("API 接收地址") },
            placeholder = { Text("https://你的域名/api/push") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            "必须填到 /api/push 这一层，只填域名会打到首页并被当成成功",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = localSecret,
            onValueChange = { localSecret = it },
            label = { Text("通讯密钥") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            "必须与服务端环境变量 APP_SECRET 完全一致，否则会被签名校验拦截（401）",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 测试连接：把失败原因区分到「解析/网络/HTTP/签名/业务」五层，
        // 避免用户在「浏览器能开、App 失败」之间反复猜。
        OutlinedButton(
            onClick = {
                val ep = localEndpoint.trim()
                if (ep.isEmpty() || !ep.startsWith("https://")) {
                    Toast.makeText(context, "请先填写合法的 https:// 地址", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                testing = true
                testResult = null
                scope.launch {
                    testResult = ConnDiagnostics.check(ep, localSecret.trim())
                    testing = false
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在测试…", fontSize = 13.sp)
            } else {
                Icon(Icons.Default.NetworkCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("测试连接", fontSize = 13.sp)
            }
        }

        testResult?.let { r ->
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (r.ok) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (r.ok) "✓ ${r.title}" else "✗ ${r.title}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (r.ok) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (r.detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            r.detail,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (r.ok) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                        )
                    }
                    if (r.hint.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "→ ${r.hint}",
                            fontSize = 11.sp,
                            color = if (r.ok) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                val ep = localEndpoint.trim()
                val sk = localSecret.trim()
                if (ep.isEmpty() || sk.isEmpty()) {
                    Toast.makeText(context, "API 地址与通讯密钥均不能为空", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (!ep.startsWith("https://")) {
                    Toast.makeText(context, "API 地址必须以 https:// 开头", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                AppStore.updateEndpoint(ep)
                AppStore.updateSecret(sk)
                onSave() // 保存并关闭面板
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(25.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("保存配置", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun PermissionCard(hasPermission: Boolean, onGrant: () -> Unit) {
    Card(
        onClick = { if (!hasPermission) onGrant() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasPermission) MaterialTheme.colorScheme.primaryContainer 
                             else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (hasPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (hasPermission) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = if (hasPermission) "系统运行中" else "未获取通知监听权限",
                    fontWeight = FontWeight.Bold,
                    color = if (hasPermission) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (hasPermission) "通知监听与后台保活已就绪" else "点击此处前往系统设置授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasPermission) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun LogTerminalCard(
    logs: List<String>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("终端日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(18.dp)) }
                IconButton(onClick = onClear) { Icon(Icons.Default.DeleteSweep, contentDescription = "清空", modifier = Modifier.size(18.dp)) }
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E)) // 纯黑极客背景
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            color = when {
                                log.contains("✅") || log.contains("💰") -> Color(0xFFA5D6A7)
                                log.contains("❌") || log.contains("🚨") -> Color(0xFFEF9A9A)
                                log.contains("⏳") -> Color(0xFFFFF59D)
                                else -> Color(0xFFB0BEC5)
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun checkPermission(context: Context): Boolean {
    return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

/**
 * 保活状态速览卡。
 *
 * 存在的理由：权限卡片只说明「授权了」，但授权 ≠ 服务正在运行。
 * 进程被冻结后权限仍在，界面却会显示「运行中」，用户完全看不出已经漏单。
 * 这里把「已授权 / 已绑定 / 后台放行 / 闹钟」四件事分开呈现。
 */
@Composable
fun KeepAliveSummaryCard(hasListener: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val ignoringBattery = remember {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }
    val exactAlarmOk = remember { !KeepAliveGuide.exactAlarmDisabled(context) }
    val bound = ListenerStatus.connected

    val issues = listOfNotNull(
        if (!hasListener) "监听权限未授予" else null,
        if (!bound) "服务未绑定" else null,
        if (!ignoringBattery) "未加电池白名单" else null,
        if (!exactAlarmOk) "精确闹钟被禁用" else null
    )

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (issues.isEmpty()) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (issues.isEmpty()) Icons.Default.BatteryFull else Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = if (issues.isEmpty()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (issues.isEmpty()) "后台保活已就绪" else "有 ${issues.size} 项保活设置待处理",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (issues.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (issues.isEmpty()) "${KeepAliveGuide.vendorLabel()} · 点击查看详情"
                    else issues.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issues.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (issues.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * 保活引导面板。
 *
 * 内容分三段：
 *  1. 系统可自动判定的状态（能查的才显示，查不到的不编）
 *  2. 按当前 ROM 给出的手动步骤（含可跳转按钮）
 *  3. 看门狗说明（让用户理解为什么应用会周期性地自检）
 */
@Composable
fun KeepAlivePanel(
    listenerGranted: Boolean,
    notificationGranted: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // 每次打开面板重新计算状态（用户可能刚从设置页返回）
    val (statusLines, manualSteps) = remember(listenerGranted, notificationGranted) {
        KeepAliveGuide.statusCheck(context, notificationGranted, listenerGranted)
    }
    val minutesSinceArm = remember { Watchdog.minutesSinceLastArm(context) }
    val exactAlarmDisabled = remember { KeepAliveGuide.exactAlarmDisabled(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
    ) {
        Text("后台保活", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "检测到设备：${KeepAliveGuide.vendorLabel()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(18.dp))

        // ---- 系统状态 ----
        Text("系统状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                statusLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                Text(
                    text = if (minutesSinceArm < 0) "· 看门狗：尚未上钟" else "· 看门狗：${minutesSinceArm} 分钟前上钟",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        // ---- 快捷开启 ----
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!listenerGranted) {
                Button(
                    onClick = { KeepAliveGuide.openListenerSettings(context) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("开启监听权限", fontSize = 13.sp) }
            }
            if (exactAlarmDisabled) {
                OutlinedButton(
                    onClick = { KeepAliveGuide.requestExactAlarm(context) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("允许精确闹钟", fontSize = 13.sp) }
            }
        }

        // ---- 手动步骤 ----
        if (manualSteps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                "还需要在系统设置里做以下操作",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "这些项系统不开放查询，无法自动判断，请逐项确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            manualSteps.forEach { step ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(step.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                step.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                        if (step.key != "lock") {
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { KeepAliveGuide.open(context, step.key) }) {
                                Text("去设置", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ---- 看门狗说明 ----
        Spacer(modifier = Modifier.height(22.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "看门狗机制",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    "本应用每 9 分钟由系统闹钟唤醒一次，检查监听服务是否仍在运行；" +
                        "若被系统冻结或回收，会自动请求重新绑定。" +
                        "间隔不设更短，是因为过于频繁的唤醒会被部分国产 ROM 判定为异常并拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp)
        ) { Text("知道了", fontSize = 15.sp) }
        Spacer(modifier = Modifier.height(12.dp))
    }
}