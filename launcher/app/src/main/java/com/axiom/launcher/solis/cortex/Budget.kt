package com.axiom.launcher.solis.cortex

import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

data class BudgetCaps(
    val dailyUsd: Double = 1.0,
    val monthlyUsd: Double = 15.0,
    /** Background narration has its own small cap so it can never crowd out real work. */
    val narratorDailyUsd: Double = 0.05,
)

data class LedgerEntry(
    val id: String,
    val ts: Long,
    val threadId: String,
    val modeId: String,
    val role: CallRole,
    val model: String,
    val costUsd: Double,
    val inputTokens: Int,
    val outputTokens: Int,
    /** True when the provider didn't report cost and it was computed from catalog prices. */
    val estimated: Boolean,
)

interface LedgerPersistence {
    fun loadLedger(since: Long): List<LedgerEntry>
    fun addLedger(e: LedgerEntry)
}

class BudgetExceeded(message: String) : Exception(message)

data class BudgetSummary(
    val today: Double,
    val month: Double,
    val byRoleToday: Map<CallRole, Double>,
    val byModeToday: Map<String, Double>,
    val callsToday: Int,
    val reserved: Double,
)

/**
 * Tracks spend and enforces caps. Before a call, the engine reserves the estimated cost; afterwards it
 * commits the actual cost. Reservations count against caps, so concurrent threads can't overspend.
 */
class BudgetLedger(private val store: LedgerPersistence, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = ArrayList<LedgerEntry>()
    private val reservations = HashMap<String, Reservation>()

    data class Reservation(val id: String, val threadId: String, val modeId: String, val role: CallRole, val amount: Double)

    @Synchronized
    fun load() {
        entries.clear()
        entries += store.loadLedger(monthStart(clock()) - 86_400_000L)
    }

    /** Remaining spend allowed for a call, considering day, month, mode and thread caps plus open reservations. */
    @Synchronized
    fun remaining(caps: BudgetCaps, modeId: String, modeDailyCap: Double?, threadId: String, threadCap: Double?, role: CallRole): Double {
        val now = clock()
        val day = dayStart(now)
        val month = monthStart(now)
        val pending = reservations.values
        val today = entries.filter { it.ts >= day }
        var left = caps.dailyUsd - today.sumOf { it.costUsd } - pending.sumOf { it.amount }
        left = minOf(left, caps.monthlyUsd - entries.filter { it.ts >= month }.sumOf { it.costUsd } - pending.sumOf { it.amount })
        if (modeDailyCap != null) left = minOf(left, modeDailyCap - today.filter { it.modeId == modeId }.sumOf { it.costUsd } -
            pending.filter { it.modeId == modeId }.sumOf { it.amount })
        if (threadCap != null) left = minOf(left, threadCap - entries.filter { it.threadId == threadId }.sumOf { it.costUsd } -
            pending.filter { it.threadId == threadId }.sumOf { it.amount })
        if (role == CallRole.NARRATOR) left = minOf(left, caps.narratorDailyUsd - today.filter { it.role == CallRole.NARRATOR }.sumOf { it.costUsd } -
            pending.filter { it.role == CallRole.NARRATOR }.sumOf { it.amount })
        return left.coerceAtLeast(0.0)
    }

    @Synchronized
    fun reserve(amount: Double, caps: BudgetCaps, modeId: String, modeDailyCap: Double?, threadId: String, threadCap: Double?, role: CallRole): Reservation {
        val left = remaining(caps, modeId, modeDailyCap, threadId, threadCap, role)
        if (amount > left + 1e-9) throw BudgetExceeded("This call (~$${"%.4f".format(java.util.Locale.US, amount)}) would exceed your spending cap ($${"%.4f".format(java.util.Locale.US, left)} left)")
        val r = Reservation(UUID.randomUUID().toString(), threadId, modeId, role, amount)
        reservations[r.id] = r
        return r
    }

    @Synchronized
    fun commit(r: Reservation?, threadId: String, modeId: String, role: CallRole, model: String, cost: Double, inTok: Int, outTok: Int, estimated: Boolean) {
        r?.let { reservations.remove(it.id) }
        val e = LedgerEntry(UUID.randomUUID().toString(), clock(), threadId, modeId, role, model, cost, inTok, outTok, estimated)
        entries += e
        store.addLedger(e)
    }

    @Synchronized
    fun release(r: Reservation?) { r?.let { reservations.remove(it.id) } }

    @Synchronized
    fun spentByThread(threadId: String): Double = entries.filter { it.threadId == threadId }.sumOf { it.costUsd }

    @Synchronized
    fun summary(): BudgetSummary {
        val now = clock()
        val day = dayStart(now)
        val today = entries.filter { it.ts >= day }
        return BudgetSummary(
            today = today.sumOf { it.costUsd },
            month = entries.filter { it.ts >= monthStart(now) }.sumOf { it.costUsd },
            byRoleToday = today.groupBy { it.role }.mapValues { (_, v) -> v.sumOf { it.costUsd } },
            byModeToday = today.groupBy { it.modeId }.mapValues { (_, v) -> v.sumOf { it.costUsd } },
            callsToday = today.size,
            reserved = reservations.values.sumOf { it.amount },
        )
    }

    @Synchronized
    fun recent(limit: Int = 50): List<LedgerEntry> = entries.takeLast(limit).reversed()

    companion object {
        fun dayStart(now: Long): Long = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun monthStart(now: Long): Long = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = dayStart(now); set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
    }
}
