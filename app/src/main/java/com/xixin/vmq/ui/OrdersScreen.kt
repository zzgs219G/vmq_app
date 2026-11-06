package com.xixin.vmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xixin.vmq.ledger.LedgerViewModel
import com.xixin.vmq.ledger.Reconciler
import com.xixin.vmq.network.RemoteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val orderTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

/**
 * 订单页：服务端订单列表 + 疑似漏发比对。
 *
 * ## 为什么这一页是整套系统的观测点
 *
 * 服务端只知道「有一笔 pending 订单」，无法区分：
 *   - 买家还没付款
 *   - 买家付了，但设备端推送失败
 *
 * 只有把设备端账本（我确实收到了这笔钱）与服务端订单放在一起比，
 * 才能找出「钱到了、卡没发」的情况。这一页做的就是这件事。
 *
 * ## 补单为什么叫「重推」
 *
 * 补单不是人工发卡，而是重走一遍原有的推送链路，由服务端按金额
 * 重新匹配订单。不新增任何绕过验资的发卡入口 —— 钱是既成事实，
 * 需要补的只是那次失败的推送。
 */
@Composable
fun OrdersScreen() {
    val vm: LedgerViewModel = viewModel()
    val context = LocalContext.current
    val orders by vm.orders.collectAsState()
    val syncState by vm.syncState.collectAsState()
    val suspicious by vm.suspicious.collectAsState()

    var filter by remember { mutableStateOf(0) }

    // 首次进入自动同步一次
    LaunchedEffect(Unit) {
        if (orders.isEmpty()) vm.syncOrders()
    }

    val shown = when (filter) {
        1 -> orders.filter { it.isPending }
        2 -> orders.filter { it.isPaid }
        else -> orders
    }.sortedByDescending { it.createTime }

    // 整页用单层 LazyColumn：头部也作为 item 参与滚动。
    // 旧实现用 Column 包头部 + LazyColumn(fillMaxSize) 包列表，
    // 一旦「疑似漏发」卡片变高（最多列 5 条），列表区被挤成 0 高度，
    // 而头部本身不可滚动 —— 表现为整页卡死，下面的订单一条也看不到。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "header-top") {
            Spacer(Modifier.height(8.dp))
        }

        item(key = "sync-bar") {
            SyncBar(syncState) { vm.syncOrders() }
        }

        if (suspicious.isNotEmpty()) {
            item(key = "suspicious") {
                Spacer(Modifier.height(4.dp))
                SuspiciousSection(
                    items = suspicious,
                    onRetry = { item -> vm.retryPush(item) },
                    onIgnore = { item -> vm.ignoreSuspicious(item) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        item(key = "filter") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == 0, { filter = 0 }, { Text("全部 ${orders.size}", fontSize = 12.sp) })
                FilterChip(
                    filter == 1, { filter = 1 },
                    { Text("待付款 ${orders.count { it.isPending }}", fontSize = 12.sp) }
                )
                FilterChip(
                    filter == 2, { filter = 2 },
                    { Text("已发货 ${orders.count { it.isPaid }}", fontSize = 12.sp) }
                )
            }
        }

        if (shown.isEmpty()) {
            item(key = "empty") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ReceiptLong, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (orders.isEmpty()) "还没有同步到订单" else "该分类下没有订单",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (orders.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "点上方刷新，或检查设置里的后台密码",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        } else {
            items(shown, key = { it.orderId }) { o -> OrderCard(o) }
        }
    }
}

@Composable
private fun SyncBar(state: LedgerViewModel.SyncState, onSync: () -> Unit) {
    val (text, color) = when (state) {
        is LedgerViewModel.SyncState.Idle -> "尚未同步" to MaterialTheme.colorScheme.onSurfaceVariant
        is LedgerViewModel.SyncState.Loading -> "正在同步…" to MaterialTheme.colorScheme.primary
        is LedgerViewModel.SyncState.Success ->
            "已同步 ${state.count} 笔 · ${orderTimeFmt.format(Date(state.at)).substring(6)}" to
                MaterialTheme.colorScheme.primary
        is LedgerViewModel.SyncState.Failure -> state.reason to MaterialTheme.colorScheme.error
        is LedgerViewModel.SyncState.NotConfigured ->
            "未配置 API 地址与密码，请到设置里填写" to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Sync, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                fontSize = 12.sp,
                color = color,
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
            if (state is LedgerViewModel.SyncState.Loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onSync, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, "刷新", Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SuspiciousSection(
    items: List<Reconciler.Suspicious>,
    onRetry: (Reconciler.Suspicious) -> Unit,
    onIgnore: (Reconciler.Suspicious) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${items.size} 笔收款可能未发货",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "已收到钱，但服务端没有对应的已发货订单",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(10.dp))

            items.take(5).forEach { s ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "¥${s.txn.amountYuan}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            orderTimeFmt.format(Date(s.txn.occurredAt)),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { onRetry(s) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("重推", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        // 不是本站订单的收款（随手转账、测试金额等）永远匹配不到订单，
                        // 没有任何自动判据能识别，必须给一个人工出口，否则红字永久残留。
                        TextButton(
                            onClick = { onIgnore(s) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "忽略",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                    Text(
                        when {
                            s.noOrder -> "服务端查不到这个金额的订单（订单可能创建失败）"
                            s.expiredOrder -> "对应订单已过期（买家付款太晚，需退款或人工处理）"
                            else -> "对应订单仍待付款（推送可能失败）"
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }
            if (items.size > 5) {
                Text(
                    "还有 ${items.size - 5} 笔，见下方账本",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OrderCard(o: RemoteOrder) {
    // 状态配色：过期用低饱和的灰蓝而非 onSurfaceVariant ——
    // 后者与卡片背景 surfaceVariant 对比度过低，几乎看不出标签。
    val statusBg: Color
    val statusFg: Color
    val statusText: String
    when {
        o.isPaid -> {
            statusText = "已发货"
            statusBg = Color(0xFF10B981).copy(alpha = 0.15f)
            statusFg = Color(0xFF059669)
        }
        o.isExpired -> {
            statusText = "已过期"
            statusBg = Color(0xFF64748B).copy(alpha = 0.18f)
            statusFg = Color(0xFF475569)
        }
        else -> {
            statusText = "待付款"
            statusBg = Color(0xFFF59E0B).copy(alpha = 0.18f)
            statusFg = Color(0xFFB45309)
        }
    }

    // 过期订单整体降低不透明度，让「已发货/待付款」在列表里更突出
    val contentAlpha = if (o.isExpired) 0.55f else 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (o.isExpired)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧状态色条：不用读文字也能一眼分辨
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(statusFg.copy(alpha = if (o.isExpired) 0.5f else 1f), RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "¥${o.payPrice}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = if (o.isExpired)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusFg,
                        modifier = Modifier
                            .background(statusBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    o.productName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(o.orderId)
                        append(" · ")
                        append(orderTimeFmt.format(Date(o.createTime)))
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * contentAlpha)
                )
            }
        }
    }
}
