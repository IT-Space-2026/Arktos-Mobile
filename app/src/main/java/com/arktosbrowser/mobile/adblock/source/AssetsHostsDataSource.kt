package com.arktosbrowser.mobile.adblock.source

import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.log.Logger
import android.content.res.AssetManager
import io.reactivex.Single

/**
 * A [HostsDataSource] that reads from the hosts list in assets.
 *
 * @param assetManager The store for application assets.
 * @param logger The logger used to log status.
 */
class AssetsHostsDataSource constructor(
    private val assetManager: AssetManager,
    private val logger: Logger
) : HostsDataSource {

    /**
     * A [Single] that reads the raw hosts file content. It can handle files that simply have a list
     * of host names to block, or it can handle a full blown hosts file — comment stripping and
     * domain extraction happen in the Rust core.
     *
     * @see HostsDataSource.loadHosts
     */
    override fun loadHosts(): Single<HostsResult> = Single.create { emitter ->
        val content = assetManager.open(BLOCKED_DOMAINS_LIST_FILE_NAME)
            .bufferedReader()
            .use { it.readText() }

        logger.log(TAG, "Loaded hosts file (${content.length} chars)")
        emitter.onSuccess(HostsResult.Success(content))
    }

    override fun identifier(): String = "assets:${BuildConfig.VERSION_CODE}"

    companion object {
        private const val TAG = "AssetsHostsDataSource"
        private const val BLOCKED_DOMAINS_LIST_FILE_NAME = "hosts.txt"
    }

}
