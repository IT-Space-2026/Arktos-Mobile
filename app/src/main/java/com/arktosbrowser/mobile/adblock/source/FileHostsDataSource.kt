package com.arktosbrowser.mobile.adblock.source

import com.arktosbrowser.mobile.extensions.onIOExceptionResumeNext
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import io.reactivex.Single
import java.io.File

/**
 * A [HostsDataSource] that loads hosts from the file found in [UserPreferences].
 *
 * @param logger The logger used to log information about the loading process.
 * @param file The file from which hosts will be loaded. Must have read access to the file.
 */
class FileHostsDataSource constructor(
    private val logger: Logger,
    private val file: File
) : HostsDataSource {

    /**
     * A [Single] that reads the raw content of a local hosts file. It can handle files that simply
     * have a list of host names to block, or it can handle a full blown hosts file — comment
     * stripping and domain extraction happen in the Rust core.
     *
     * @see HostsDataSource.loadHosts
     */
    override fun loadHosts(): Single<HostsResult> = Single.create<HostsResult> { emitter ->
        val content = file.bufferedReader().use { it.readText() }

        logger.log(TAG, "Loaded hosts file (${content.length} chars)")
        emitter.onSuccess(HostsResult.Success(content))
    }.onIOExceptionResumeNext { HostsResult.Failure(it) }

    companion object {
        private const val TAG = "FileHostsDataSource"
    }

}
