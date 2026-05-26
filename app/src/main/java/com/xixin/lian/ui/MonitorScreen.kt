package com.xixin.lian.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.xixin.lian.network.KtorClient
import com.xixin.lian.store.AppStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by AppStore.logs.collectAsState()
    val endpoint by AppStore.apiEndpoint.collectAsState()
    val secretKey by AppStore.secretKey.collectAsState()
    
    val hasPermission by AppStore.hasNotificationPermission.collectAsState()
    
    // 控制底部设置面板的显示状态
    var showSettingsSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("V免签监控", fontWeight = FontWeight.Bold) },
                actions = {
                    // 低频操作入口：放在右上角，不占空间
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "通讯设置")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // 1. 核心权限状态卡片（醒目）
            PermissionCard(hasPermission) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // 2. 高频测试区（直接在主页，方便点击）
            QuickTestCard()

            Spacer(modifier = Modifier.height(16.dp))

            // 3. 运行日志区（占据剩余全部屏幕，互不干扰）
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
    var localEndpoint by remember { mutableStateOf(endpoint) }
    var localSecret by remember { mutableStateOf(secretKey) }

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
            placeholder = { Text("https://...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = localSecret,
            onValueChange = { localSecret = it },
            label = { Text("通讯密钥") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                AppStore.updateEndpoint(localEndpoint)
                AppStore.updateSecret(localSecret)
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