// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import com.arktosbrowser.mobile.browser.AppLockState
import com.arktosbrowser.mobile.browser.IncognitoPartition
import com.arktosbrowser.mobile.browser.SecureWindowPolicy
import com.arktosbrowser.mobile.database.bookmark.BookmarkExporter
import com.arktosbrowser.mobile.database.bookmark.BookmarkRepository
import com.arktosbrowser.mobile.device.BuildInfo
import com.arktosbrowser.mobile.device.BuildType
import com.arktosbrowser.mobile.di.AppComponent
import com.arktosbrowser.mobile.di.DaggerAppComponent
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.engine.EngineSelector
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.DeveloperPreferences
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.utils.FileUtils
import com.arktosbrowser.mobile.utils.MemoryLeakUtils
import com.arktosbrowser.mobile.utils.installMultiDex
import com.arktosbrowser.mobile.view.WebViewEngine
import android.os.StrictMode
import com.google.android.material.color.DynamicColors
import io.reactivex.rxjava3.core.Scheduler
import okhttp3.OkHttp
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import javax.inject.Inject
import kotlin.system.exitProcess

class BrowserApp : Application() {

    @Inject internal lateinit var developerPreferences: DeveloperPreferences
    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject internal lateinit var bookmarkModel: BookmarkRepository
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject internal lateinit var logger: Logger
    @Inject internal lateinit var buildInfo: BuildInfo
    @Inject internal lateinit var webViewEngine: WebViewEngine

    lateinit var applicationComponent: AppComponent

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT < 21) {
            installMultiDex(context = base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            Class.forName("com.arktosbrowser.mobile.sync.SyncWorker")
                .getMethod("schedule", android.content.Context::class.java)
                .invoke(null, this)
        }
        OkHttp.initialize(this)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build())
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            FileUtils.writeCrashToStorage(this, ex)
            // Also drop a copy in the app-specific external dir so it
            // can be retrieved without root (Files app / adb pull).
            try {
                val dir = java.io.File(getExternalFilesDir(null), "crash")
                dir.mkdirs()
                java.io.File(
                    dir,
                    ex.javaClass.simpleName + '_' + System.currentTimeMillis() + ".txt"
                ).printWriter().use { w -> ex.printStackTrace(w) }
            } catch (_: Throwable) {
            }

            // DIAG — also drop a copy into the public Downloads dir via
            // MediaStore so the report is retrievable on devices where
            // Android/data is locked down and adb is unavailable.
            try {
                val trace = ex.javaClass.name + ": " + (ex.message ?: "") +
                    "\n" + android.util.Log.getStackTraceString(ex)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "arkos-crash.log")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )?.let { uri ->
                    contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(trace.toByteArray()) }
                }
            } catch (_: Throwable) {
            }

            // DIAG — surface the trace on screen in a :crash-process
            // activity that outlives this process's teardown.
            try {
                startActivity(
                    android.content.Intent(
                        this,
                        com.arktosbrowser.mobile.crash.CrashActivity::class.java
                    )
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(
                            com.arktosbrowser.mobile.crash.CrashActivity.EXTRA_TRACE,
                            android.util.Log.getStackTraceString(ex)
                        )
                )
            } catch (_: Throwable) {
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                exitProcess(2)
            }
        }

        RxJavaPlugins.setErrorHandler { throwable: Throwable? ->
            if (BuildConfig.DEBUG && throwable != null) {
                FileUtils.writeCrashToStorage(this, throwable)
                throw throwable
            }
        }

        // GeckoView spawns its own service processes (:crashhelper,
        // :gpu, :tab, :media, ...) which all run this Application
        // class — creating a second GeckoRuntime there races the
        // main profile's creation ("Could not find profile folder")
        // and brings the whole browser down. Only the browser
        // processes — main and :incognito — get the full init.
        if (!isBrowserProcess()) {
            return
        }

        applicationComponent = DaggerAppComponent.builder()
                .application(this)
                .buildInfo(createBuildInfo())
                .build()
        injector.inject(this)

        // ENG02/ENG03 — the flavor's engine backend registers itself
        // through the engine registry (webview flavor → WebViewEngine,
        // gecko flavor → GeckoEngine); initialize() carries the
        // process-wide setup and still runs before the first page is
        // created.
        Engines.install(EngineSelector.createBackend(this))
        Engines.backend.initialize(this)

        Single.fromCallable(bookmarkModel::count)
                .filter { it == 0L }
                .flatMapCompletable {
                    val assetsBookmarks = BookmarkExporter.importBookmarksFromAssets(this@BrowserApp)
                    bookmarkModel.addBookmarkList(assetsBookmarks)
                }
                .subscribeOn(databaseScheduler)
                .subscribe()

        registerActivityLifecycleCallbacks(object : MemoryLeakUtils.LifecycleAdapter() {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // SEC07 — extends the FLAG_SECURE decision to windows
                // outside BrowserActivity (settings, reading mode, …)
                // so the whole-app toggle really covers every window.
                // Dispatched inside performCreate, before the window
                // is first drawn.
                if (SecureWindowPolicy.shouldSecure(
                        activity is IncognitoActivity,
                        userPreferences.secureWindowsEnabled
                    )
                ) {
                    activity.window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivityCount <= 0) {
                    startedActivityCount = 0
                    // SEC01 — last activity stopped = the app is fully
                    // backgrounded; the app-lock re-lock countdown runs
                    // from this point.
                    AppLockState.onAppBackgrounded()
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                logger.log(TAG, "Cleaning up after the Android framework")
                MemoryLeakUtils.clearNextServedView(activity, this@BrowserApp)
            }
        })

        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    /**
     * Only the main and `:incognito` processes host the browser —
     * GeckoView's service processes (`:crashhelper`, `:gpu`, `:tab`,
     * …) share this [Application] class and must skip the Dagger +
     * engine init or they race the real runtime's profile creation.
     */
    private fun isBrowserProcess(): Boolean {
        val processName = currentProcessName() ?: return true
        return processName == packageName ||
                IncognitoPartition.isIncognitoProcess(packageName, processName)
    }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }
                ?.processName
        }

    /**
     * Create the [BuildType] from the [BuildConfig].
     */
    private var startedActivityCount = 0

    private fun createBuildInfo() = BuildInfo(when {
        BuildConfig.DEBUG -> BuildType.DEBUG
        else -> BuildType.RELEASE
    })

    companion object {
        private const val TAG = "BrowserApp"

        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(Build.VERSION.SDK_INT == Build.VERSION_CODES.KITKAT)
        }
    }

}