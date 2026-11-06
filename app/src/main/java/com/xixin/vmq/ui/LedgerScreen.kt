package com.xixin.vmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.vmq.ledger.LedgerViewModel
import com.xixin.vmq.ledger.Txn
import com.xixin.vmq.ledger.TxnParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 本地账本页。
 *
 * ## 为什么把「待确认」放在最上面
 *
 * 通知监听必然产生两类模糊记录：
 *  1. 只解析出金额、判不出收支方向（如微信「转账」）
 *  2. 疑似重复（软合并会处理大部分，但仍有边界情况）
 *
 * 这些必须由用户点一下才能入账。如果藏在二级页面，用户不会去处理，
 * 账本就会长期停留在「一堆待确认」的状态而失去意义。所以放首页顶部，
 * 并用醒目的数量提示。
 */
@Composable
fun LedgerScreen() {
    val vm: LedgerViewModel = viewModel()
    val pending by vm.pending.collectAsState()
    val confirmed by vm.confirmed.collectAsState()
    val pushFailed by vm.pushFailed.collectAsState()
    val stats by vm.stats.collectAsState()

    var showManualAdd by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }

    val list = if (tab == 0) pending else confirmed

    // 整页单层 LazyColumn：头部（统计卡、横幅、Tab）都作为 item 参与滚动。
    // 旧实现把它们固定在顶部、列表用 fillMaxSize，头部一高列表就被挤没，
    // 且头部本身不能滑动 —— 表现为整页卡住。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "stat") {
            Spacer(Modifier.height(8.dp))
            StatCard(
                todayIn = stats.todayInYuan,
                todayCount = stats.todayCount,
                weekIn = stats.weekInYuan,
                monthIn = stats.monthInYuan,
                todayOut = stats.todayOutYuan
            )
        }

        // 推送失败警示（这是自动发卡链路唯一的兜底观测点）
        if (pushFailed.isNotEmpty()) {
            item(key = "push-failed") {
                PushFailedBanner(
                    count = pushFailed.size,
                    onDismissAll = {
                        pushFailed.forEach { vm.dismissPushFailure(it.id) }
                    }
                )
            }
        }

        item(key = "tabs") {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = {
                        Text(
                            if (pending.isEmpty()) "待确认" else "待确认 ${pending.size}",
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = if (pending.isNotEmpty()) {
                        { Icon(Icons.Default.PendingActions, null, Modifier.size(16.dp)) }
                    } else null
                )
                // 方向已判定的记录可以批量确认，省去逐条点击。
                // 只对 direction 明确（非 UNKNOWN）的记录生效 —— 方向不明的必须人工判断。
                if (tab == 0 && pending.count { it.direction != Txn.Direction.UNKNOWN } >= 2) {
                    AssistChip(
                        onClick = {
                            pending.filter { it.direction != Txn.Direction.UNKNOWN }
                                .forEach { vm.confirm(it.id, null) }
                        },
                        label = { Text("全部确认", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.DoneAll, null, Modifier.size(15.dp))
                        }
                    )
                }
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text("已入账 ${confirmed.size}", fontSize = 13.sp) }
                )
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = { showManualAdd = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "手动补录", Modifier.size(20.dp))
                }
            }
        }

        if (list.isEmpty()) {
            item(key = "empty") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ReceiptLong, null,
                            Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (tab == 0) "没有待确认的流水" else "还没有入账记录",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        if (tab == 1) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "收到收款通知后会出现在「待确认」里",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        } else {
            items(list, key = { it.id }) { txn ->
                if (tab == 0) {
                    PendingTxnCard(
                        txn = txn,
                        onConfirmIn = { vm.confirm(txn.id, Txn.Direction.IN) },
                        onConfirmOut = { vm.confirm(txn.id, Txn.Direction.OUT) },
                        onIgnore = { vm.ignore(txn.id) }
                    )
                } else {
                    ConfirmedTxnCard(
                        txn = txn,
                        onDelete = { vm.delete(txn.id) }
                    )
                }
            }
        }
    }

    if (showManualAdd) {
        ManualAddDialog(
            onDismiss = { showManualAdd = false },
            onConfirm = { cents, direction, note ->
                vm.addManual(cents, direction, note)
                showManualAdd = false
            }
        )
    }
}

@Composable
private fun StatCard(
    todayIn: String,
    todayCount: Int,
    weekIn: String,
    monthIn: String,
    todayOut: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "今日收入",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "¥",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    todayIn,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$todayCount 笔",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                MiniStat("本周收入", "¥$weekIn", Modifier.weight(1f))
                MiniStat("本月收入", "¥$monthIn", Modifier.weight(1f))
                MiniStat("今日支出", "¥$todayOut", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun PushFailedBanner(count: Int, onDismissAll: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CloudOff, null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "有 $count 笔收款推送失败",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                // 双设备场景下这里绝大多数是另一台手机已经推成功了，
                // 这台只是慢了一步。写清楚，避免每条都去后台核对。
                Text(
                    "若另一台设备已发货，可全部清除",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                )
            }
            TextButton(
                onClick = onDismissAll,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "清除",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun PendingTxnCard(
    txn: Txn,
    onConfirmIn: () -> Unit,
    onConfirmOut: () -> Unit,
    onIgnore: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (txn.source == Txn.Source.ALIPAY) Icons.Default.AccountBalanceWallet
                    else Icons.Default.Chat,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (txn.source == Txn.Source.ALIPAY) "支付宝" else "微信",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    timeFmt.format(Date(txn.occurredAt)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "¥${txn.amountYuan}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = if (txn.direction == Txn.Direction.OUT)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )

            // 原始通知：让用户能判断这是收入还是支出
            if (txn.rawText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    txn.rawText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                )
            }

            if (txn.direction == Txn.Direction.UNKNOWN) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠️ 无法判断收支方向，请选择",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            // 方向已确定的记录只需一次点击「确认入账」，不必在两个按钮间选。
            // 原实现无论方向是否明确都摆两个按钮，用户每笔都要判断按哪个，负担过重。
            if (txn.direction == Txn.Direction.UNKNOWN) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onConfirmIn,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) { Text("记为收入", fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = onConfirmOut,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) { Text("记为支出", fontSize = 12.sp) }

                    TextButton(
                        onClick = onIgnore,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) { Text("忽略", fontSize = 12.sp) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (txn.direction == Txn.Direction.OUT) onConfirmOut() else onConfirmIn() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) { Text("确认入账", fontSize = 12.sp) }

                    TextButton(
                        onClick = onIgnore,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("忽略", fontSize = 12.sp) }
                }
            }

            if (txn.note.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    txn.note,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ConfirmedTxnCard(txn: Txn, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "¥${txn.amountYuan}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        color = if (txn.direction == Txn.Direction.OUT)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    if (txn.pushState == Txn.PushState.SUCCESS) {
                        Icon(
                            Icons.Default.CloudDone, "已推送",
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else if (txn.pushState == Txn.PushState.FAILED) {
                        Icon(
                            Icons.Default.CloudOff, "推送失败",
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(if (txn.source == Txn.Source.ALIPAY) "支付宝" else if (txn.source == Txn.Source.WECHAT) "微信" else "手动")
                        append(" · ")
                        append(timeFmt.format(Date(txn.occurredAt)))
                        txn.linkedOrderId?.let { append(" · $it") }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (txn.pushState == Txn.PushState.FAILED && txn.pushError.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        txn.pushError,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 13.sp) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (cents: Long, direction: String, note: String) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Txn.Direction.IN) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动补录", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "用于通知漏抓时补记一笔",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = direction == Txn.Direction.IN,
                        onClick = { direction = Txn.Direction.IN },
                        label = { Text("收入", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = direction == Txn.Direction.OUT,
                        onClick = { direction = Txn.Direction.OUT },
                        label = { Text("支出", fontSize = 12.sp) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cents = TxnParser.toCents(amount)
                    if (cents > 0) onConfirm(cents, direction, note)
                },
                enabled = amount.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
