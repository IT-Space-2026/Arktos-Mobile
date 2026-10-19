package com.arktosbrowser.mobile.view

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.LayoutInflater
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import com.arktosbrowser.mobile.permissions.PermissionsManager
import com.arktosbrowser.mobile.permissions.PermissionsResultAction
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.di.DiskScheduler
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.DialogItem
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.favicon.FaviconModel
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.view.webrtc.WebRtcPermissionsModel
import com.arktosbrowser.mobile.view.webrtc.WebRtcPermissionsView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.core.Scheduler
import javax.inject.Inject

class ArktosCookieChromeClient(
        private val activity: Activity,
        private val arktosCookieView: ArktosCookieView
) : WebChromeClient(), WebRtcPermissionsView {

    private val geoLocationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private val uiController: UIController
    @Inject internal lateinit var faviconModel: FaviconModel
    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject internal lateinit var webRtcPermissionsModel: WebRtcPermissionsModel
    @Inject internal lateinit var logger: Logger
    @Inject @field:DiskScheduler internal lateinit var diskScheduler: Scheduler

    init {
        activity.injector.inject(this)
        uiController = activity as UIController
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        if (arktosCookieView.isShown) {
            uiController.updateProgress(newProgress)
        }
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        arktosCookieView.titleInfo.setFavicon(icon)
        uiController.tabChanged(arktosCookieView)
        cacheFavicon(view.url, icon)
    }

    /**
     * Naive caching of the favicon according to the domain name of the URL
     *
     * @param icon the icon to cache
     */
    private fun cacheFavicon(url: String?, icon: Bitmap?) {
        if (icon == null || url == null) {
            return
        }

        faviconModel.cacheFaviconForUrl(icon, url)
            .subscribeOn(diskScheduler)
            .subscribe()
    }


    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (title?.isNotEmpty() == true) {
            arktosCookieView.titleInfo.setTitle(title)
        } else {
            arktosCookieView.titleInfo.setTitle(activity.getString(R.string.untitled))
        }
        uiController.tabChanged(arktosCookieView)
        if (view != null && view.url != null) {
            uiController.updateHistory(title, view.url.orEmpty())
        }
    }

    override fun requestPermissions(permissions: Set<String>, onGrant: (Boolean) -> Unit) {
        val missingPermissions = permissions
            .filter { !PermissionsManager.instance.hasPermission(activity, it) }

        if (missingPermissions.isEmpty()) {
            onGrant(true)
        } else {
            PermissionsManager.instance.requestPermissionsIfNecessaryForResult(
                    activity,
                    missingPermissions.toTypedArray(),
                    object : PermissionsResultAction() {
                        override fun onGranted() = onGrant(true)

                        override fun onDenied(permission: String) = onGrant(false)
                    }
            )
        }
    }

    override fun requestResources(source: String,
                                  resources: Array<String>,
                                  onGrant: (Boolean) -> Unit) {
        activity.runOnUiThread {
            val resourcesString = resources.joinToString(separator = "\n")
            BrowserDialog.showPositiveNegativeDialog(
                    activity = activity,
                    title = R.string.title_permission_request,
                    message = R.string.message_permission_request,
                    messageArguments = arrayOf(source, resourcesString),
                    positiveButton = DialogItem(title = R.string.action_allow) { onGrant(true) },
                    negativeButton = DialogItem(title = R.string.action_dont_allow) { onGrant(false) },
                    onCancel = { onGrant(false) }
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onPermissionRequest(request: PermissionRequest) {
        if (userPreferences.webRtcEnabled) {
            webRtcPermissionsModel.requestPermission(request, this)
        } else {
            request.deny()
        }
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String,
                                                    callback: GeolocationPermissions.Callback) =
        PermissionsManager.instance.requestPermissionsIfNecessaryForResult(activity, geoLocationPermissions, object : PermissionsResultAction() {
            override fun onGranted() {
                val remember = true
                MaterialAlertDialogBuilder(activity).apply {
                    setTitle(activity.getString(R.string.location))
                    val org = if (origin.length > 50) {
                        "${origin.subSequence(0, 50)}..."
                    } else {
                        origin
                    }
                    setMessage(org + activity.getString(R.string.message_location))
                    setCancelable(true)
                    setPositiveButton(activity.getString(R.string.action_allow)) { _, _ ->
                        callback.invoke(origin, true, remember)
                    }
                    setNegativeButton(activity.getString(R.string.action_dont_allow)) { _, _ ->
                        callback.invoke(origin, false, remember)
                    }
                }.resizeAndShow()
            }

            override fun onDenied(permission: String) =//TODO show message and/or turn off setting
                    Unit
        })

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                                resultMsg: Message): Boolean {
        // Only claim the window request when the transport is deliverable and
        // the new tab actually gets created. Otherwise decline so the WebView
        // loads the link in the current window (upstream #306 — a malformed
        // transport or a tab-creation failure crashed on the unchecked cast).
        if (!NewWindowTransport.isDeliverable(resultMsg)) {
            return false
        }
        return try {
            uiController.onCreateWindow(resultMsg)
        } catch (e: Exception) {
            logger.log(TAG, "Failed to create window for transport", e)
            false
        }
    }

    override fun onCloseWindow(window: WebView) = uiController.onCloseWindow(arktosCookieView)

    @Suppress("unused", "UNUSED_PARAMETER")
    fun openFileChooser(uploadMsg: ValueCallback<Uri>) = uiController.openFileChooser(uploadMsg)

    @Suppress("unused", "UNUSED_PARAMETER")
    fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String) =
        uiController.openFileChooser(uploadMsg)

    @Suppress("unused", "UNUSED_PARAMETER")
    fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String, capture: String) =
        uiController.openFileChooser(uploadMsg)

    override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
                                   fileChooserParams: FileChooserParams): Boolean {
        uiController.showFileChooser(filePathCallback)
        return true
    }

    fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Obtain an image that is displayed as a placeholder on a video until the video has initialized
     * and can begin loading.
     *
     * @return a Bitmap that can be used as a place holder for videos.
     */
    override fun getDefaultVideoPoster(): Bitmap? {
        if(isPackageInstalled(activity.resources.getString(R.string.ytdl_package_name), activity.packageManager)){
            activity.findViewById<FrameLayout>(R.id.download_button).visibility = View.VISIBLE
        }
        return if (super.getDefaultVideoPoster() == null) {
            BitmapFactory.decodeResource(activity.resources,
                    android.R.drawable.spinner_background)
        } else {
            super.getDefaultVideoPoster()
        }
    }
    /**
     * Inflate a view to send to a LightningView when it needs to display a video and has to
     * show a loading dialog. Inflates a progress view and returns it.
     *
     * @return A view that should be used to display the state
     * of a video's loading progress.
     */
    override fun getVideoLoadingProgressView(): View =
        LayoutInflater.from(activity).inflate(R.layout.video_loading_progress, null)

    override fun onHideCustomView() = uiController.onHideCustomView()

    override fun onShowCustomView(view: View, callback: CustomViewCallback) =
        uiController.onShowCustomView(view, callback)

    override fun onShowCustomView(view: View, requestedOrientation: Int,
                                  callback: CustomViewCallback) =
        uiController.onShowCustomView(view, callback, requestedOrientation)

    companion object {
        private const val TAG = "ArktosCookieChromeClient"
    }

}
