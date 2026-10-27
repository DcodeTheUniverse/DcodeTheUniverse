package com.axiom.launcher.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.axiom.launcher.AxiomApp
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * A safety net for coding jobs: if Android stops Axiom while Claude is still working, this wakes it every
 * ~15 minutes (only with a network) so finished work and failed checks still reach the user. While Axiom
 * runs, the bridge's own loop does the polling and this does next to nothing.
 */
class CodingPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val bridge = (applicationContext as AxiomApp).solis.coding
        withTimeoutOrNull(10_000) { bridge.awaitLoaded() }
        runCatching { bridge.pollDue() }
        return Result.success()
    }

    companion object {
        private const val NAME = "coding-poll"

        /** Keeps the periodic check scheduled only while some job is still active. */
        fun sync(context: Context, anyActive: Boolean) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (anyActive) {
                val req = PeriodicWorkRequestBuilder<CodingPollWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            } else wm.cancelUniqueWork(NAME)
        }
    }
}

/** Wakes Axiom about every 15 minutes (with a network) while a half-price batch request is waiting for its answer. */
class BatchPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { (applicationContext as AxiomApp).solis.pollBatches() }
        return Result.success()
    }

    companion object {
        private const val NAME = "batch-poll"

        fun sync(context: Context, anyWaiting: Boolean) {
            val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            if (anyWaiting) {
                val req = PeriodicWorkRequestBuilder<BatchPollWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            } else wm.cancelUniqueWork(NAME)
        }
    }
}
