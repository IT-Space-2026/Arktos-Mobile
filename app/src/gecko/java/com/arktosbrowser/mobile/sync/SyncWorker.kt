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
import com.arktosbrowser.mobile.rust.ArktosCore
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
            repo.install(root, applicationContext)
            // Inbound first — frames the transport caught since the
            // last run become imported ops the appliers consume.
            ArktosCore.syncTransportDrain(root)
            // Engine-side retry — ops that landed pending before the
            // appliers registered (or whose apply failed) re-offer
            // through the registered lanes for every synced store.
            listOf("bookmarks", "history", "tabs", "settings", "passwords", "syncmeta")
                .forEach { runCatching { ArktosCore.syncDrainPending(root, it) } }
            // Kotlin fallback lane for the two stores whose ops the
            // adapters don't consume natively here.
            listOf("bookmarks", "history").forEach { repo.drainPending(root, it) }
            // Imported logins materialized in the Rust vault reflect
            // into the Kotlin store gecko autofills from.
            repo.mergePasswords(root, com.arktosbrowser.mobile.passwords.LoginVaults.get(applicationContext))
            // Imported settings land in SharedPreferences — the echo
            // guard keeps each applied write from re-emitting.
            repo.mergeSettings(
                root,
                applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            )
            // Outbound — the exported op-log rides one frame to every
            // live link (member endpoints get dialed on demand).
            ArktosCore.syncTransportPush(root)
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
                    .install(context.filesDir.absolutePath, context.applicationContext)
            }
            // Emit on every non-excluded preference write — the
            // settings lane's outbound half.
            runCatching {
                com.arktosbrowser.mobile.sync.SyncBridge.attachSettings(
                    context.getSharedPreferences("settings", Context.MODE_PRIVATE),
                    context.filesDir.absolutePath
                )
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    // No point waking the transport while offline —
                    // every drain/push would be a wasted no-op.
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }

        /** One immediate exchange round — pairing confirm calls this
         *  so the first sync doesn't wait out the periodic window. */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }
    }
}
