// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arktosbrowser.mobile.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DIAG-only boot/crash capture — every entry point no-ops unless the
 * build set `-ParktosDiag` (BuildConfig.DIAG). Built for the gecko
 * launch crash that the normal uncaught-exception surface can't see:
 *
 *  - [earlyInit] runs in attachBaseContext — before any
 *    ContentProvider — so provider-init crashes are caught too.
 *  - [dumpExitReasons] reads our own historical exit reasons
 *    (API 30+): a REASON_CRASH_NATIVE record carries the tombstone
 *    backtrace — the only window into a Rust abort.
 *  - [checkpoint] appends boot milestones to Downloads/arkos-boot.log;
 *    the last line shows exactly how far the process got before dying.
 */
object Diag {

    private const val BOOT_LOG = "arkos-boot.log"
    private const val EXIT_LOG = "arkos-exit.log"
    private const val RUST_PANIC_LOG = "arktos-rust-panic.log"

    @Volatile private var initialized = false
    private val boot = StringBuilder()
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** First line of defense — call from Application.attachBaseContext. */
    fun earlyInit(app: Context) {
        if (!BuildConfig.DIAG || initialized) return
        initialized = true
        installHandler(app)
        checkpoint(app, "attachBaseContext")
        runCatching { copyIfExists(app, "arktos-rust-panic.log", RUST_PANIC_LOG) }
        runCatching { dumpGeckoCrashReports(app) }
        runCatching { dumpExitReasons(app) }
    }

    /**
     * Install the reporting uncaught-exception handler. Called early
     * by [earlyInit] in diag builds and again by BrowserApp.onCreate
     * (which passes the platform default it captured first).
     */
    fun installHandler(app: Context, chain: Thread.UncaughtExceptionHandler? = null) {
        val prev = chain ?: Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            report(app, thread, ex)
            if (prev != null && prev !== Thread.getDefaultUncaughtExceptionHandler()) {
                prev.uncaughtException(thread, ex)
            } else {
                kotlin.system.exitProcess(2)
            }
        }
    }

    /** Record a boot milestone — visible even after a native death. */
    fun checkpoint(ctx: Context, tag: String) {
        if (!BuildConfig.DIAG) return
        runCatching {
            synchronized(boot) {
                boot.append(ts.format(Date())).append(' ')
                    .append(android.os.Process.myPid()).append(' ')
                    .append(tag).append('\n')
                writeDownloads(ctx, BOOT_LOG, boot.toString().toByteArray())
            }
        }
    }

    /** The fatal-Java path — every step guarded so nothing can silence it. */
    private fun report(app: Context, thread: Thread, ex: Throwable) {
        val trace = ex.javaClass.name + ": " + (ex.message ?: "") +
            "\n" + android.util.Log.getStackTraceString(ex)
        runCatching {
            val dir = File(app.filesDir, "crash").apply { mkdirs() }
            File(dir, ex.javaClass.simpleName + '_' + System.currentTimeMillis() + ".txt")
                .printWriter().use { ex.printStackTrace(it) }
        }
        runCatching { writeDownloads(app, "arkos-crash.log", trace.toByteArray()) }
        runCatching {
            app.startActivity(
                android.content.Intent(app, CrashActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CrashActivity.EXTRA_TRACE, trace)
            )
        }
    }

    /**
     * Dump this package's recent exit records — REASON_CRASH_NATIVE
     * entries carry the tombstone (native backtrace + fault addr),
     * REASON_ANR/REASON_CRASH give the description. Requires API 30;
     * older devices just get a note in the log.
     */
    private fun dumpExitReasons(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            writeDownloads(app, EXIT_LOG, "device < API 30 — no exit reasons\n".toByteArray())
            return
        }
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 10) ?: return
        val out = StringBuilder()
        for (info in exits) {
            out.append("=== ").append(ts.format(Date(info.timestamp)))
                .append(" pid=").append(info.pid)
                .append(" reason=").append(reasonName(info.reason))
                .append(" (").append(info.reason).append(')')
                .append(" status=").append(info.status)
                .append(" importance=").append(info.importance)
                .append('\n')
            info.description?.let { out.append(it).append('\n') }
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                info.reason == ApplicationExitInfo.REASON_ANR
            ) {
                runCatching {
                    info.traceInputStream?.use { stream ->
                        out.append(stream.readBytes().toString(Charsets.UTF_8))
                    }
                }
            }
            out.append('\n')
        }
        writeDownloads(app, EXIT_LOG, out.toString().toByteArray())
    }

    /** GeckoView's own minidump reports — unreadable binary but the
     *  `.extra` file holds JSON crash metadata. */
    private fun dumpGeckoCrashReports(app: Context) {
        val mozilla = File(app.filesDir, "mozilla")
        if (!mozilla.isDirectory) return
        mozilla.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".extra") || it.name.endsWith(".dmp")) }
            .sortedByDescending { it.lastModified() }
            .take(4)
            .forEach { f ->
                runCatching {
                    if (f.name.endsWith(".extra")) {
                        writeDownloads(app, "arkos-gecko-${f.name}.txt", f.readBytes())
                    }
                }
            }
    }

    /** Mirror a files-dir artifact into Downloads (next-launch delivery). */
    private fun copyIfExists(app: Context, name: String, outName: String) {
        val f = File(app.filesDir, name)
        if (f.isFile) writeDownloads(app, outName, f.readBytes())
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "REASON_$reason"
    }

    /**
     * Write [bytes] as our own [name] under public Downloads, replacing
     * a same-named copy we wrote earlier (avoids "(1)" duplicates).
     * API 29+ goes through MediaStore; older devices fall back to the
     * public dir path directly (WRITE_EXTERNAL_STORAGE era).
     */
    private fun writeDownloads(ctx: Context, name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            var uri = queryOwnDownload(ctx, name)
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }
            uri?.let { cr.openOutputStream(it, "wt")?.use { s -> s.write(bytes) } }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
    }

    private fun queryOwnDownload(ctx: Context, name: String): android.net.Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.OWNER_PACKAGE_NAME
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        return ctx.contentResolver.query(
            collection, projection, selection, arrayOf(name), null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == ctx.packageName) {
                    return android.net.Uri.withAppendedPath(collection, c.getLong(0).toString())
                }
            }
            null
        }
    }
}
