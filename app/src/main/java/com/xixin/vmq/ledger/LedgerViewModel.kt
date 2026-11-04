package com.xixin.vmq.ledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 账本页面状态。
 */
data class LedgerUiState(
    val pending: List<Txn> = emptyList(),
    val confirmed: List<Txn> = emptyList(),
    val pushFailed: List<Txn> = emptyList(),
    val todayInCents: Long = 0,
    val todayCount: Int = 0,
    val weekInCents: Long = 0,
    val monthInCents: Long = 0,
    val todayOutCents: Long = 0
) {
    val todayInYuan: String get() = TxnParser.toYuan(todayInCents)
    val weekInYuan: String get() = TxnParser.toYuan(weekInCents)
    val monthInYuan: String get() = TxnParser.toYuan(monthInCents)
    val todayOutYuan: String get() = TxnParser.toYuan(todayOutCents)
}

/**
 * 账本 ViewModel。
 *
 * ## 时间边界为什么在 ViewModel 里算
 *
 * 「今天/本周/本月」的起止时刻每次进入页面都应以当前时间为准，
 * 但如果放进 Composable 里，重组会反复重算并重建 Flow，导致频繁查询。
 * 这里在 ViewModel 初始化时算一次，页面存续期间固定。
 */
class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LedgerRepository(app)

    private val dayStart = startOfDay()
    private val weekStart = startOfWeek()
    private val monthStart = startOfMonth()

    /** 强制刷新触发器（确认/忽略/删除后推进，让统计重新查一次） */
    private val refreshTick = MutableStateFlow(0)

    val pending: StateFlow<List<Txn>> = repo.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val confirmed: StateFlow<List<Txn>> = repo.observeConfirmed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pushFailed: StateFlow<List<Txn>> = repo.observePushFailed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<LedgerUiState> = refreshTick.combine(
        repo.sumIn(dayStart, Long.MAX_VALUE)
    ) { _, todayIn ->
        LedgerUiState(todayInCents = todayIn)
    }.combine(repo.sumIn(weekStart, Long.MAX_VALUE)) { s, weekIn ->
        s.copy(weekInCents = weekIn)
    }.combine(repo.sumIn(monthStart, Long.MAX_VALUE)) { s, monthIn ->
        s.copy(monthInCents = monthIn)
    }.combine(repo.sumOut(dayStart, Long.MAX_VALUE)) { s, todayOut ->
        s.copy(todayOutCents = todayOut)
    }.combine(repo.countIn(dayStart, Long.MAX_VALUE)) { s, count ->
        s.copy(todayCount = count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    // ---- 操作 ----

    fun confirm(id: Long, direction: String? = null) = viewModelScope.launch {
        repo.confirm(id, direction)
        refreshTick.value++
    }

    fun ignore(id: Long) = viewModelScope.launch {
        repo.ignore(id)
        refreshTick.value++
    }

    fun delete(id: Long) = viewModelScope.launch {
        repo.delete(id)
        refreshTick.value++
    }

    fun updateNote(id: Long, note: String) = viewModelScope.launch {
        repo.updateNote(id, note)
    }

    fun addManual(amountCents: Long, direction: String, note: String) = viewModelScope.launch {
        repo.recordManual(amountCents, direction, note)
        refreshTick.value++
    }

    fun markPushSuccess(id: Long, orderId: String?) = viewModelScope.launch {
        repo.markPushSuccess(id, orderId)
    }

    fun markPushFailed(id: Long, error: String) = viewModelScope.launch {
        repo.markPushFailed(id, error)
    }

    // ---- 时间边界 ----

    private fun startOfDay(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 以周一为一周开始（国内习惯） */
    private fun startOfWeek(): Long {
        val c = Calendar.getInstance()
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfMonth(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
