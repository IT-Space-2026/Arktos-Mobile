// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arktosbrowser.mobile.di.injector
import java.util.concurrent.TimeUnit

/**
 * MSYNC07 — the opportunistic sync drain: within Android's background
 * limits, a 15-minute periodic worker applies pending ops into the
 * stores (the apply half that's real today). Store-and-forward is
 * inherent — the op-log holds ops until the transport drains them,
 * so missed windows just apply later.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val root = applicationContext.filesDir.absolutePath
            val repo = SyncRepository(applicationContext.injector.arktosStore())
            // Bring the adapter lanes up before draining — installs
            // the shared store into the Rust shims and registers the
            // appliers that route imported ops into it.
            repo.install(root)
            listOf("bookmarks", "history").forEach { repo.drainPending(root, it) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "sync-drain"

        /** Schedule the periodic drain — call once at app start. */
        fun schedule(context: Context) {
            // Bring the adapter lanes up at launch too — emission
            // starts working immediately, the 15-min worker only
            // drains the wire.
            runCatching {
                SyncRepository(context.injector.arktosStore())
                    .install(context.filesDir.absolutePath)
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }
}
