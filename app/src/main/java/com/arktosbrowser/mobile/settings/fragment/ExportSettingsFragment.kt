/*
 * Copyright 2014 A.C.R. Development
 */
package com.arktosbrowser.mobile.settings.fragment

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context.ACTIVITY_SERVICE
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.arktosbrowser.mobile.MainActivity
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.bookmark.LegacyBookmarkImporter
import com.arktosbrowser.mobile.bookmark.NetscapeBookmarkFormatImporter
import com.arktosbrowser.mobile.browser.PendingTabTransfer
import com.arktosbrowser.mobile.database.bookmark.BookmarkExporter
import com.arktosbrowser.mobile.database.bookmark.BookmarkRepository
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.DialogItem
import com.arktosbrowser.mobile.dialog.TapjackingGuard
import com.arktosbrowser.mobile.extensions.snackbar
import com.arktosbrowser.mobile.extensions.toast
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.utils.BackupCrypto
import com.arktosbrowser.mobile.utils.Utils
import com.arktosbrowser.mobile.utils.Utils.close
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.json.JSONObject
import java.io.*
import javax.inject.Inject


class ExportSettingsFragment : AbstractSettingsFragment() {

    @Inject
    internal lateinit var bookmarkRepository: BookmarkRepository

    @Inject
    internal lateinit var application: Application

    @Inject
    internal lateinit var userPreferences: UserPreferences

    @Inject
    internal lateinit var netscapeBookmarkFormatImporter: NetscapeBookmarkFormatImporter

    @Inject
    internal lateinit var legacyBookmarkImporter: LegacyBookmarkImporter

    @Inject
    @field:DatabaseScheduler
    internal lateinit var databaseScheduler: Scheduler

    @Inject
    @field:MainScheduler
    internal lateinit var mainScheduler: Scheduler

    @Inject
    internal lateinit var logger: Logger

    private var importSubscription: Disposable? = null
    private var exportSubscription: Disposable? = null
    private var bookmarksSortSubscription: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        // All file access goes through the Storage Access Framework
        // (ACTION_CREATE/OPEN_DOCUMENT), which needs no runtime
        // permissions — the old READ/WRITE_EXTERNAL_STORAGE request is
        // always denied at targetSdk 30+ and broke every entry in this
        // screen.

        switchPreference(
            preference = SETTINGS_BOOKMARK_SORT_DATE,
            isChecked = userPreferences.bookmarkSortByDate,
            onCheckChange = { userPreferences.bookmarkSortByDate = it }
        )

        clickablePreference(preference = SETTINGS_EXPORT) { showExportBookmarksDialog() }
        clickablePreference(preference = SETTINGS_IMPORT) { showImportBookmarksDialog() }
        clickablePreference(
            preference = SETTINGS_DELETE_BOOKMARKS,
            onClick = this::deleteAllBookmarks
        )

        clickablePreference(preference = SETTINGS_TAB_EXPORT) { showExportTabsDialog() }
        clickablePreference(preference = SETTINGS_TAB_IMPORT) { showImportTabsDialog() }

        clickablePreference(preference = SETTINGS_SETTINGS_EXPORT) { showExportSettingsDialog() }
        clickablePreference(preference = SETTINGS_SETTINGS_IMPORT) { showImportSettingsDialog() }

        clickablePreference(preference = SETTINGS_DELETE_SETTINGS, onClick = this::clearSettings)

        clickablePreference(preference = SETTINGS_BACKUP_EXPORT) { showEncryptedExportDialog() }
        clickablePreference(preference = SETTINGS_BACKUP_IMPORT) { showEncryptedImportDialog() }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_bookmarks)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        exportSubscription?.dispose()
        importSubscription?.dispose()
        bookmarksSortSubscription?.dispose()
    }

    override fun onDestroy() {
        super.onDestroy()

        exportSubscription?.dispose()
        importSubscription?.dispose()
        bookmarksSortSubscription?.dispose()
    }

    private fun showError(@StringRes message: Int) {
        val activity = activity
        if (activity != null && !activity.isFinishing && isAdded) {
            Utils.createInformativeDialog(activity, R.string.title_error, message)
        } else {
            application.toast(message)
        }
    }

    private fun showImportTabsDialog() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain"))
        }
        tabsImportFilePicker.launch(intent)
    }

    private val tabsImportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val inputStream = result.data?.data
                ?.let { context?.contentResolver?.openInputStream(it) }
            if (inputStream == null) {
                showError(R.string.import_tabs_error)
                return@registerForActivityResult
            }

            importSubscription?.dispose()
            importSubscription = Single.fromCallable {
                inputStream.bufferedReader().useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { extractTabUrl(it) }
                        .toList()
                }
            }
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribeBy(
                    onSuccess = { urls ->
                        if (urls.isEmpty()) {
                            showError(R.string.import_tabs_error)
                            return@subscribeBy
                        }
                        // Tabs must be created inside the browser activity —
                        // hand the URL list over (in-process handoff, SEC09)
                        // and let it doAfterInitialization.
                        PendingTabTransfer.post(PendingTabTransfer.Request.Import(urls))
                        startActivity(Intent(context, MainActivity::class.java))
                    },
                    onError = {
                        logger.log(TAG, "onError: importing tabs", it)
                        showError(R.string.import_tabs_error)
                    }
                )
        }

    /**
     * Accepts the exported `{"url": ..., "title": ...}` lines and, for
     * hand-edited files, bare URLs. Only http(s) links are imported.
     */
    private fun extractTabUrl(line: String): String? {
        val url = runCatching { JSONObject(line).optString("url") }
            .getOrDefault("")
            .ifBlank { line }
        return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun showExportTabsDialog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "TabsExport.txt")
        }
        tabsExportFilePicker.launch(intent)
    }

    private val tabsExportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val uri = result.data?.data
            if (uri == null) {
                showError(R.string.bookmark_export_failure)
                return@registerForActivityResult
            }
            // The TabsManager injected here is a fresh instance — only
            // the browser activity holds the live tab list, so hand it
            // the destination URI (in-process handoff, SEC09) and let
            // it serialize.
            PendingTabTransfer.post(PendingTabTransfer.Request.Export(uri))
            startActivity(Intent(context, MainActivity::class.java))
        }

    private fun clearSettings() {
        val builder = MaterialAlertDialogBuilder(activity as Activity)
        builder.setTitle(getString(R.string.confirm))
        builder.setMessage(getString(R.string.clear))


        builder.setPositiveButton(resources.getString(R.string.action_ok)) { dialogInterface, which ->
            Toast.makeText(
                getActivity(),
                R.string.reset_settings, Toast.LENGTH_LONG
            ).show()

            val handler = Handler()
            handler.postDelayed(Runnable {
                (activity?.getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                    .clearApplicationUserData()
            }, 500)
        }
        builder.setNegativeButton(resources.getString(R.string.action_cancel)) { _, _ ->
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun showExportSettingsDialog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "SettingsExport.txt")
        }
        settingsExportFilePicker.launch(intent)
    }

    private val settingsExportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val outputStream = result.data?.data
                ?.let { context?.contentResolver?.openOutputStream(it) }
            if (outputStream == null) {
                showError(R.string.error)
                return@registerForActivityResult
            }
            val userPref = application.getSharedPreferences("settings", 0)
            val allEntries: Map<String, *> = userPref.all

            exportSubscription?.dispose()
            exportSubscription = Completable.fromAction {
                var settingsWriter: BufferedWriter? = null
                try {
                    settingsWriter = BufferedWriter(OutputStreamWriter(outputStream))
                    for (entry in allEntries) {
                        // Type tag keeps int/long/float distinct on import
                        // — untyped (legacy) files can only guess by regex
                        // and truncated e.g. timestamps to Int.
                        val (type, value) = when (val v = entry.value) {
                            is Int -> "int" to v
                            is Long -> "long" to v
                            is Float -> "float" to v.toDouble()
                            is Boolean -> "bool" to v
                            else -> "string" to v.toString()
                        }
                        settingsWriter.write(
                            JSONObject()
                                .put("key", entry.key)
                                .put("type", type)
                                .put("value", value)
                                .toString()
                        )
                        settingsWriter.newLine()
                    }
                } finally {
                    close(settingsWriter)
                }
            }
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribeBy(
                    onComplete = { activity?.snackbar(getString(R.string.action_ok)) },
                    onError = {
                        logger.log(TAG, "onError: exporting settings", it)
                        showError(R.string.error)
                    }
                )
        }

    private fun showExportBookmarksDialog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "BookmarksExport.txt")
        }
        bookmarkExportFilePicker.launch(intent)
    }

    private val bookmarkExportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val outputStream = result.data?.data
                ?.let { context?.contentResolver?.openOutputStream(it) }
            if (outputStream == null) {
                showError(R.string.bookmark_export_failure)
                return@registerForActivityResult
            }

            bookmarksSortSubscription = bookmarkRepository.getAllBookmarksSorted()
                .subscribeOn(databaseScheduler)
                .subscribeBy(
                    onSuccess = { list ->
                        if (!isAdded) {
                            return@subscribeBy
                        }

                        exportSubscription?.dispose()
                        exportSubscription =
                            BookmarkExporter.exportBookmarksToFile(list, outputStream)
                                .subscribeOn(databaseScheduler)
                                .observeOn(mainScheduler)
                                .subscribeBy(
                                    onComplete = {
                                        activity?.snackbar(getString(R.string.action_ok))
                                    },
                                    onError = {
                                        logger.log(TAG, "onError: exporting bookmarks", it)
                                        showError(R.string.bookmark_export_failure)
                                    }
                                )
                    },
                    onError = {
                        logger.log(TAG, "onError: reading bookmarks for export", it)
                        showError(R.string.bookmark_export_failure)
                    }
                )
        }

    private fun deleteAllBookmarks() {
        showDeleteBookmarksDialog()
    }

    private fun showDeleteBookmarksDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = activity as Activity,
            title = R.string.action_delete,
            message = R.string.action_delete_all_bookmarks,
            positiveButton = DialogItem(title = R.string.yes) {
                bookmarkRepository
                    .deleteAllBookmarks()
                    .subscribeOn(databaseScheduler)
                    .subscribeBy(
                        onComplete = {},
                        onError = {
                            logger.log(TAG, "onError: deleting bookmarks", it)
                        }
                    )
            },
            negativeButton = DialogItem(title = R.string.no) {},
            onCancel = {}
        )
    }

    private fun showImportSettingsDialog() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/plain"
                )
            )
        }
        settingsImportFilePicker.launch(intent)
    }

    private val settingsImportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val inputStream = result.data?.data
                ?.let { context?.contentResolver?.openInputStream(it) }
            if (inputStream == null) {
                showError(R.string.error)
                return@registerForActivityResult
            }
            importSubscription?.dispose()
            importSubscription = Single.fromCallable {
                var settingsReader: BufferedReader? = null
                try {
                    settingsReader = BufferedReader(InputStreamReader(inputStream))
                    // Parse the whole file BEFORE touching prefs — the old
                    // code cleared settings first, so a corrupt file left
                    // the app fully reset.
                    settingsReader.lineSequence()
                        .filter { it.isNotBlank() }
                        .map { parseSettingsEntry(JSONObject(it)) }
                        .toList()
                } finally {
                    close(settingsReader)
                }
            }
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribeBy(
                    onSuccess = { entries ->
                        val userPref = application.getSharedPreferences("settings", 0)
                        val editor = userPref.edit()
                        editor.clear()
                        entries.forEach { applyEntry(editor, it) }
                        editor.apply()
                        activity?.snackbar(getString(R.string.action_ok))
                    },
                    onError = {
                        logger.log(TAG, "onError: importing settings", it)
                        showError(R.string.error)
                    }
                )
        }

    /** Typed (new) or regex-guessed (legacy export) settings entry. */
    private sealed class SettingEntry {
        abstract val key: String

        data class IntEntry(override val key: String, val value: Int) : SettingEntry()
        data class LongEntry(override val key: String, val value: Long) : SettingEntry()
        data class FloatEntry(override val key: String, val value: Float) : SettingEntry()
        data class BoolEntry(override val key: String, val value: Boolean) : SettingEntry()
        data class StringEntry(override val key: String, val value: String) : SettingEntry()
    }

    private fun parseSettingsEntry(json: JSONObject): SettingEntry {
        val key = json.getString("key")
        return when (json.optString("type")) {
            "int" -> SettingEntry.IntEntry(key, json.getInt("value"))
            "long" -> SettingEntry.LongEntry(key, json.getLong("value"))
            "float" -> SettingEntry.FloatEntry(key, json.getDouble("value").toFloat())
            "bool" -> SettingEntry.BoolEntry(key, json.getBoolean("value"))
            "string" -> SettingEntry.StringEntry(key, json.getString("value"))
            else -> {
                // Legacy exports carried no type — fall back to the old
                // shape heuristic. Long/float fidelity is lost for those
                // files by design of the format.
                val value = json.getString("value")
                when {
                    value.matches("-?\\d+".toRegex()) -> SettingEntry.IntEntry(key, value.toInt())
                    value == "true" || value == "false" -> SettingEntry.BoolEntry(key, value.toBoolean())
                    else -> SettingEntry.StringEntry(key, value)
                }
            }
        }
    }

    private fun applyEntry(
        editor: android.content.SharedPreferences.Editor,
        entry: SettingEntry
    ) {
        when (entry) {
            is SettingEntry.IntEntry -> editor.putInt(entry.key, entry.value)
            is SettingEntry.LongEntry -> editor.putLong(entry.key, entry.value)
            is SettingEntry.FloatEntry -> editor.putFloat(entry.key, entry.value)
            is SettingEntry.BoolEntry -> editor.putBoolean(entry.key, entry.value)
            is SettingEntry.StringEntry -> editor.putString(entry.key, entry.value)
        }
    }

    // ------------------------------------------------------------------
    // SEC02 — encrypted backup archive
    //
    // Android Auto Backup copies files verbatim and cannot run a
    // password prompt, so the password-protected restore path lives
    // here: settings JSONL + bookmark JSONL wrapped in one JSON payload
    // sealed by BackupCrypto (PBKDF2 → AES-256-GCM).
    // ------------------------------------------------------------------

    private fun showEncryptedExportDialog() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "ArktosBackup.abk")
        }
        backupExportFilePicker.launch(intent)
    }

    private val backupExportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val uri = result.data?.data
            if (uri == null) {
                showError(R.string.bookmark_export_failure)
                return@registerForActivityResult
            }
            showBackupPasswordDialog { password ->
                exportEncryptedBackup(uri, password)
            }
        }

    /** Two-field password prompt; the dialog stays open on mismatch. */
    private fun showBackupPasswordDialog(onPassword: (String) -> Unit) {
        val activity = activity ?: return
        val v = activity.layoutInflater.inflate(R.layout.dialog_backup_password, null)
        val passwordField = v.findViewById<android.widget.EditText>(R.id.backup_password)
        val confirmField = v.findViewById<android.widget.EditText>(R.id.backup_password_confirm)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_password)
            .setView(v)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        TapjackingGuard.protect(dialog, TapjackingGuard.Surface.BACKUP_PASSWORD)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = passwordField.text.toString()
            when {
                password.isEmpty() ->
                    passwordField.error = getString(R.string.backup_password_empty)
                password != confirmField.text.toString() ->
                    confirmField.error = getString(R.string.backup_passwords_differ)
                else -> {
                    dialog.dismiss()
                    onPassword(password)
                }
            }
        }
    }

    private fun exportEncryptedBackup(uri: android.net.Uri, password: String) {
        exportSubscription?.dispose()
        exportSubscription = bookmarkRepository.getAllBookmarksSorted()
            .subscribeOn(databaseScheduler)
            .flatMap { list ->
                Single.fromCallable {
                    val bookmarksJson = ByteArrayOutputStream().also { bos ->
                        BookmarkExporter.exportBookmarksToFile(list, bos).blockingAwait()
                    }.toString(Charsets.UTF_8.name())

                    JSONObject()
                        .put("v", 1)
                        .put("settings", collectSettingsJsonl())
                        .put("bookmarks", bookmarksJson)
                        .toString()
                }
            }
            .map { BackupCrypto.encrypt(it.toByteArray(Charsets.UTF_8), password) }
            .flatMapCompletable { blob ->
                Completable.fromAction {
                    // "wt" truncates the placeholder doc CREATE_DOCUMENT made;
                    // application context survives fragment detach.
                    val stream = application.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("can't open backup destination")
                    stream.use { it.write(blob) }
                }
            }
            .observeOn(mainScheduler)
            .subscribeBy(
                onComplete = { activity?.snackbar(getString(R.string.backup_export_done)) },
                onError = {
                    logger.log(TAG, "onError: exporting encrypted backup", it)
                    showError(R.string.bookmark_export_failure)
                }
            )
    }

    /** Typed settings entries as JSONL — same format as settings export. */
    private fun collectSettingsJsonl(): String {
        val userPref = application.getSharedPreferences("settings", 0)
        return userPref.all.entries.joinToString("\n") { entry ->
            val (type, value) = when (val v = entry.value) {
                is Int -> "int" to v
                is Long -> "long" to v
                is Float -> "float" to v.toDouble()
                is Boolean -> "bool" to v
                else -> "string" to v.toString()
            }
            JSONObject()
                .put("key", entry.key)
                .put("type", type)
                .put("value", value)
                .toString()
        }
    }

    private fun showEncryptedImportDialog() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        backupImportFilePicker.launch(intent)
    }

    private val backupImportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val uri = result.data?.data ?: return@registerForActivityResult
            val activity = activity ?: return@registerForActivityResult
            val v = activity.layoutInflater.inflate(R.layout.password, null)
            val passwordField = v.findViewById<android.widget.EditText>(R.id.password)
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.backup_password)
                .setView(v)
                .setPositiveButton(R.string.action_ok) { _, _ ->
                    importEncryptedBackup(uri, passwordField.text.toString())
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            TapjackingGuard.protect(dialog, TapjackingGuard.Surface.BACKUP_PASSWORD)
        }

    private fun importEncryptedBackup(uri: android.net.Uri, password: String) {
        importSubscription?.dispose()
        importSubscription = Single.fromCallable {
            val blob = application.contentResolver.openInputStream(uri)
                ?.use { it.readBytes() }
                ?: throw BackupCrypto.BackupCryptoException("unreadable backup file")
            val payload = JSONObject(
                String(BackupCrypto.decrypt(blob, password), Charsets.UTF_8)
            )
            val bookmarks = legacyBookmarkImporter.importBookmarks(
                ByteArrayInputStream(
                    payload.optString("bookmarks").toByteArray(Charsets.UTF_8)
                )
            )
            val settings = payload.optString("settings").lineSequence()
                .filter { it.isNotBlank() }
                .map { parseSettingsEntry(JSONObject(it)) }
                .toList()
            bookmarks to settings
        }
            .flatMap { (bookmarks, settings) ->
                bookmarkRepository.addBookmarkList(bookmarks)
                    .andThen(Single.just(bookmarks.size to settings))
            }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { (_, settings) ->
                    // Merge into the existing prefs rather than clearing —
                    // a partial archive can't wipe the live settings.
                    val editor = application
                        .getSharedPreferences("settings", 0).edit()
                    settings.forEach { applyEntry(editor, it) }
                    editor.apply()
                    activity?.snackbar(getString(R.string.backup_import_done))
                },
                onError = {
                    logger.log(TAG, "onError: importing encrypted backup", it)
                    showError(R.string.backup_decrypt_error)
                }
            )
    }

    private fun showImportBookmarksDialog() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "text/html",
                    "text/plain"
                )
            )
        }
        bookmarkImportFilePicker.launch(intent)
    }

    private val bookmarkImportFilePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val uri = result.data?.data
            val inputStream = uri?.let { context?.contentResolver?.openInputStream(it) }
            if (inputStream == null) {
                showError(R.string.import_bookmark_error)
                return@registerForActivityResult
            }
            val mimeType = uri.let { context?.contentResolver?.getType(it) }
            importSubscription?.dispose()
            importSubscription = Single.fromCallable {
                // Reported MIME types lie — browsers' Netscape exports
                // routinely arrive as text/plain or octet-stream. Sniff
                // the content instead and fall back to the mime hint.
                val text = inputStream.bufferedReader().readText()
                val bytes = text.toByteArray(Charsets.UTF_8)
                val importer = when {
                    text.contains("<DL", ignoreCase = true) ||
                        text.contains("NETSCAPE-Bookmark", ignoreCase = true) ->
                        netscapeBookmarkFormatImporter
                    text.trimStart().startsWith("{") ||
                        text.trimStart().startsWith("[") ->
                        legacyBookmarkImporter
                    mimeType == "text/html" -> netscapeBookmarkFormatImporter
                    else -> legacyBookmarkImporter
                }
                importer.importBookmarks(ByteArrayInputStream(bytes))
            }
                .flatMap {
                    bookmarkRepository.addBookmarkList(it).andThen(Single.just(it.size))
                }
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribeBy(
                    onSuccess = { count ->
                        activity?.apply {
                            snackbar("$count ${getString(R.string.message_import)}")
                        }
                    },
                    onError = {
                        logger.log(TAG, "onError: importing bookmarks", it)
                        showError(R.string.import_bookmark_error)
                    }
                )
        }

    companion object {

        private const val TAG = "BookmarkSettingsFrag"

        private const val SETTINGS_BOOKMARK_SORT_DATE = "bookmark_sort_date"
        private const val SETTINGS_EXPORT = "export_bookmark"
        private const val SETTINGS_IMPORT = "import_bookmark"
        private const val SETTINGS_TAB_EXPORT = "export_tab"
        private const val SETTINGS_TAB_IMPORT = "import_tab"
        private const val SETTINGS_DELETE_BOOKMARKS = "delete_bookmarks"
        private const val SETTINGS_SETTINGS_EXPORT = "export_settings"
        private const val SETTINGS_SETTINGS_IMPORT = "import_settings"
        private const val SETTINGS_DELETE_SETTINGS = "clear_settings"
        private const val SETTINGS_BACKUP_EXPORT = "export_backup_encrypted"
        private const val SETTINGS_BACKUP_IMPORT = "import_backup_encrypted"
    }
}
