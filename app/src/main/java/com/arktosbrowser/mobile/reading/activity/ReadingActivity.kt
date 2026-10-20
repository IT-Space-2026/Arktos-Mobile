// SPDX-License-Identifier: MPL-2.0
// Copyright 2020 CookieJarApps and Arktos contributors
package com.arktosbrowser.mobile.reading.activity

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.isDark
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog.setDialogSize
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.utils.ThemeUtils
import com.arktosbrowser.mobile.view.ProxyUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.URL
import java.util.Locale
import javax.inject.Inject


class ReadingActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView

    @Inject
    lateinit var userPreferences: UserPreferences

    private var tts: TextToSpeech? = null
    private var invertColors = false
    private var reading = false
    private var currentUrl: String? = null
    private var fromFile = false
    private var textSizeIndex = 0
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        this.injector.inject(this)
        overridePendingTransition(R.anim.slide_in_from_right, R.anim.fade_out_scale)
        invertColors = userPreferences.invertColors
        tts = TextToSpeech(this, this)
        val color: Int
        val theme = userPreferences.resolvedTheme(this)
        if (invertColors) {
            if (!theme.isDark) {
                setTheme(R.style.Theme_SettingsTheme_Black)
            } else {
                setTheme(R.style.Theme_SettingsTheme)
            }
            color = ThemeUtils.getPrimaryColor(this)
            window.setBackgroundDrawable(ColorDrawable(color))
        } else {
            when (theme) {
                AppTheme.LIGHT -> setTheme(R.style.Theme_SettingsTheme)
                AppTheme.DARK -> setTheme(R.style.Theme_SettingsTheme_Dark)
                AppTheme.ARKTOS_LIGHT -> setTheme(R.style.Theme_SettingsTheme_Arktos)
                AppTheme.ARKTOS_DARK -> setTheme(R.style.Theme_SettingsTheme_Arktos_Dark)
                else -> setTheme(R.style.Theme_SettingsTheme_Black)
            }
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reading_view)
        bodyView = findViewById(R.id.textViewBody)
        titleView = findViewById(R.id.textViewTitle)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        textSizeIndex = userPreferences.readingTextSize
        bodyView.textSize = getTextSize(textSizeIndex)
        titleView.text = getString(R.string.untitled)
        bodyView.text = getString(R.string.loading)
        titleView.visibility = View.INVISIBLE
        bodyView.visibility = View.INVISIBLE
        loadPage(intent)
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reading, menu)

        if (menu is MenuBuilder) {
            menu.setOptionalIconsVisible(true)
        }

        return super.onCreateOptionsMenu(menu)
    }

    /**
     * Fetch + extract the readable article off the main thread — the
     * coroutine successor of the old `LoadData : AsyncTask`
     * (deprecated since API 30). Lifecycle-bound: leaving the
     * activity cancels the work.
     */
    private fun loadArticle() {
        val url = currentUrl ?: return
        lifecycleScope.launch {
            val article = withContext(Dispatchers.IO) {
                try {
                    // Route the fetch through the configured proxy — a
                    // bare URL.openStream() would bypass Tor/I2P/manual
                    // proxying.
                    val proxy = ProxyUtils.connectionProxy(userPreferences)
                    val pageUrl = URL(url)
                    val htmlData = pageUrl.openConnection(proxy).inputStream
                        .bufferedReader()
                        .use { it.readText() }
                    // PORT01 — extraction lives in the Rust core
                    // (dom_smoothie); url is just needed to resolve
                    // relative urls.
                    ArktosCore.extractReadableArticle(url, htmlData)
                } catch (e: IOException) {
                    Log.e(TAG, "reader fetch failed for $url", e)
                    null
                }
            }

            try {
                val html = article?.content
                    ?.replace("image copyright".toRegex(),
                        resources.getString(R.string.reading_mode_image_copyright) + " ")
                    ?.replace("image caption".toRegex(),
                        resources.getString(R.string.reading_mode_image_caption) + " ")
                    ?.replace("￼".toRegex(), "")
                val doc = Jsoup.parse(html ?: "")
                for (element in doc.select("img")) {
                    element.remove()
                }
                setText(article?.title, doc.outerHtml())
            } catch (e: Exception) {
                titleView.alpha = 1.0f
                titleView.visibility = View.VISIBLE
                titleView.text = resources.getString(R.string.title_error)
            }
            dismissProgressDialog()
        }
    }

    private fun makeLinkClickable(strBuilder: SpannableStringBuilder, span: URLSpan) {
        val start: Int = strBuilder.getSpanStart(span)
        val end: Int = strBuilder.getSpanEnd(span)
        val flags: Int = strBuilder.getSpanFlags(span)
        val linkColor = ThemeUtils.getAccentColor(this)
        val clickable: ClickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                titleView.text = getString(R.string.untitled)
                bodyView.text = getString(R.string.loading)
                currentUrl = span.url
                loadArticle()
            }

            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                // Theme-aware: the framework link color is unreadable on
                // dark/AMOLED surfaces — the accent reads on all of them.
                ds.color = linkColor
            }
        }
        strBuilder.setSpan(clickable, start, end, flags)
        strBuilder.removeSpan(span)
    }

    private fun setTextViewHTML(text: TextView, html: String?) {
        val sequence: CharSequence = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        val strBuilder = SpannableStringBuilder(sequence)
        val urls: Array<URLSpan> = strBuilder.getSpans(0, sequence.length, URLSpan::class.java)
        for (span in urls) {
            makeLinkClickable(strBuilder, span)
        }
        text.text = strBuilder
        text.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun loadPage(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }
        currentUrl = intent.getStringExtra(LOAD_READING_URL)
        fromFile = intent.getBooleanExtra(LOAD_FILE, false)
        val url = currentUrl ?: return false
        if (fromFile) {
            setText(url, loadFile(this, url))
            return false
        }
        supportActionBar?.title = ArktosCore.domainName(url)
        showProgressDialog()
        loadArticle()
        return true
    }

    private fun showProgressDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .show()
        progressDialog = dialog
        setDialogSize(this, dialog)
    }

    private fun dismissProgressDialog() {
        progressDialog?.takeIf { it.isShowing }?.dismiss()
        progressDialog = null
    }

    private fun setText(title: String?, body: String?) {
        if (titleView.visibility == View.INVISIBLE) {
            titleView.alpha = 1.0f
            titleView.visibility = View.VISIBLE
        }
        setTextViewHTML(titleView, title)
        if (bodyView.visibility == View.INVISIBLE) {
            bodyView.alpha = 1.0f
            bodyView.visibility = View.VISIBLE
        }
        setTextViewHTML(bodyView, body)
    }

    override fun onDestroy() {
        dismissProgressDialog()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            overridePendingTransition(R.anim.fade_in_scale, R.anim.slide_out_to_right)
        }
    }

    fun translate(lang: String?) {
        if (userPreferences.translationEndpoint.isBlank()) {
            Toast.makeText(this@ReadingActivity, resources.getString(R.string.error), Toast.LENGTH_LONG).show()
            return
        }
        val client = OkHttpClient()

        val spanned = bodyView.text as Spanned

        showProgressDialog()

        val formBody: RequestBody = ArktosCore.translateRequestBody(
            listOf(Html.toHtml(spanned, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)), "auto", lang ?: "en"
        ).toRequestBody("application/json".toMediaType())
        val translateUrl = userPreferences.translationEndpoint
        val request = Request.Builder()
                .url(ArktosCore.translateUrl(translateUrl))
                .addHeader("Accept", "application/json")
                .method("POST", formBody)
                .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "reader translate failed", e)
                runOnUiThread {
                    dismissProgressDialog()
                    Toast.makeText(this@ReadingActivity, resources.getString(R.string.error), Toast.LENGTH_LONG).show()
                }
            }

            @SuppressLint("SetTextI18n")
            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        dismissProgressDialog()
                        Toast.makeText(
                            this@ReadingActivity,
                            resources.getString(R.string.error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    throw IOException("Unexpected code $response")
                } else {
                    val values = response.body.string()
                    runOnUiThread {
                        try {
                            val translated = ArktosCore.parseTranslateResponse(values).first()
                            bodyView.text =
                                Html.fromHtml(translated, Html.FROM_HTML_MODE_COMPACT)
                            dismissProgressDialog()
                        } catch (ignored: Exception) {
                        }
                    }
                }
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.invert_item -> {
                userPreferences.invertColors = !invertColors
                currentUrl?.let {
                    launch(this, it, fromFile)
                    finish()
                }
            }
            R.id.translate_item -> {
                val builderSingle = MaterialAlertDialogBuilder(this@ReadingActivity)
                builderSingle.setTitle(resources.getString(R.string.translate_to))
                val arrayAdapter = ArrayAdapter<String>(this@ReadingActivity, android.R.layout.select_dialog_singlechoice)
                arrayAdapter.addAll("English", "Français", "Português", "Português do Brasil", "Italiano")
                val languages = arrayOf("en", "fr", "pt-pt", "pt-br", "it")
                builderSingle.setNegativeButton("cancel") { dialog: DialogInterface, _: Int -> dialog.dismiss() }
                builderSingle.setAdapter(arrayAdapter) { _: DialogInterface?, which: Int -> translate(languages[which]) }
                builderSingle.show()
            }
            R.id.text_size_item -> {
                val view = LayoutInflater.from(this).inflate(R.layout.dialog_seek_bar, null)
                val bar = view.findViewById<SeekBar>(R.id.text_size_seekbar)
                bar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar, size: Int, user: Boolean) {
                        bodyView.textSize = getTextSize(size)
                    }

                    override fun onStartTrackingTouch(arg0: SeekBar) {}
                    override fun onStopTrackingTouch(arg0: SeekBar) {}
                })
                bar.max = 5
                bar.progress = textSizeIndex
                val builder = MaterialAlertDialogBuilder(this)
                        .setView(view)
                        .setTitle(R.string.size)
                        .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                            textSizeIndex = bar.progress
                            bodyView.textSize = getTextSize(textSizeIndex)
                            userPreferences.readingTextSize = bar.progress
                        }
                val dialog: Dialog = builder.show()
                setDialogSize(this, dialog)
            }
            R.id.download -> {
                saveFile(this, Html.toHtml(bodyView.text as Spanned, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL), titleView.text.toString())
            }
            R.id.open -> {
                val builderSingle = MaterialAlertDialogBuilder(this@ReadingActivity)
                builderSingle.setTitle(resources.getString(R.string.action_open) + ":")
                val arrayAdapter = ArrayAdapter<String>(this@ReadingActivity, android.R.layout.select_dialog_singlechoice)

                val saved = savedReadingFiles()
                saved.forEach { arrayAdapter.add(it) }

                builderSingle.setPositiveButton(resources.getString(R.string.action_cancel)) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
                builderSingle.setNegativeButton(resources.getString(R.string.action_delete)) { dialog: DialogInterface, _: Int -> dialog.dismiss(); deleteDialog() }
                builderSingle.setAdapter(arrayAdapter) { _: DialogInterface?, which: Int ->
                    setTextViewHTML(bodyView, loadFile(this, saved[which]))
                    titleView.text = saved[which]
                    fromFile = true
                    currentUrl = saved[which]
                }
                builderSingle.show()
            }
            R.id.tts -> {
                if (reading) {
                    tts?.stop()
                    reading = !reading
                }
                if (bodyView.selectionEnd < 1) {
                    Toast.makeText(this, resources.getString(R.string.select_translate_text), Toast.LENGTH_LONG).show()
                    return false
                }
                val source = bodyView.text.substring(bodyView.selectionStart, bodyView.selectionEnd)
                if (source == "") {
                    Toast.makeText(this, resources.getString(R.string.select_tts_text), Toast.LENGTH_LONG).show()
                    return false
                }
                reading = !reading
                if (reading) {
                    tts?.speak(source, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
                }
                invalidateOptionsMenu()
            }
            else -> finish()
        }
        return super.onOptionsItemSelected(item)
    }

    fun deleteDialog() {
        val builderSingle = MaterialAlertDialogBuilder(this@ReadingActivity)
        builderSingle.setTitle(resources.getString(R.string.action_delete) + ":")
        val arrayAdapter = ArrayAdapter<String>(this@ReadingActivity, android.R.layout.select_dialog_singlechoice)

        val saved = savedReadingFiles()
        saved.forEach { arrayAdapter.add(it) }

        builderSingle.setAdapter(arrayAdapter) { _: DialogInterface?, which: Int -> delete(saved[which]) }

        builderSingle.setPositiveButton(resources.getString(R.string.action_cancel)) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
        builderSingle.show()
    }

    /**
     * Saved reading pages are `<name>.txt` files in internal storage;
     * the dialog shows them extension-free.
     */
    private fun savedReadingFiles(): List<String> =
        filesDir.list { _, name -> name.endsWith(".txt") }
            ?.map { it.removeSuffix(".txt") }
            ?: emptyList()

    fun saveFile(context: Context, text: String?, name: String?): Boolean {
        return try {
            context.openFileOutput("$name.txt", Context.MODE_PRIVATE)
                .writer()
                .use { it.write(text) }
            true
        } catch (e: IOException) {
            Log.e(TAG, "failed saving reading page $name", e)
            false
        }
    }

    fun delete(name: String?): Boolean {
        return File(filesDir, "$name.txt").delete()
    }

    fun loadFile(context: Context, name: String?): String? {
        return try {
            context.openFileInput("$name.txt")
                .bufferedReader()
                .use { it.readText() }
        } catch (e: IOException) {
            Log.e(TAG, "failed loading reading page $name", e)
            null
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result: Int = tts?.setLanguage(Locale.US)
                ?: TextToSpeech.LANG_NOT_SUPPORTED

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onError(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    runOnUiThread {
                        reading = false
                        invalidateOptionsMenu()
                    }
                }
            })

            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language is not supported")
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.tts)
        item?.title = resources.getString(
            if (reading) R.string.stop_tts else R.string.tts
        )
        return super.onPrepareOptionsMenu(menu)
    }

    companion object {
        private const val TAG = "ReadingActivity"
        private const val LOAD_READING_URL = "ReadingUrl"
        private const val LOAD_FILE = "FileUrl"
        private const val TTS_UTTERANCE_ID = "arktos-reading"

        /**
         * Launches this activity with the necessary URL argument.
         *
         * @param context The context needed to launch the activity.
         * @param url     The URL that will be loaded into reading mode.
         */
        fun launch(context: Context, url: String, file: Boolean) {
            val intent = Intent(context, ReadingActivity::class.java)
            intent.putExtra(LOAD_READING_URL, url)
            intent.putExtra(LOAD_FILE, file)
            context.startActivity(intent)
        }

        private const val XXLARGE = 30.0f
        private const val XLARGE = 26.0f
        private const val LARGE = 22.0f
        private const val MEDIUM = 18.0f
        private const val SMALL = 14.0f
        private const val XSMALL = 10.0f
        private fun getTextSize(size: Int): Float {
            return when (size) {
                0 -> XSMALL
                1 -> SMALL
                2 -> MEDIUM
                3 -> LARGE
                4 -> XLARGE
                5 -> XXLARGE
                else -> MEDIUM
            }
        }
    }
}
