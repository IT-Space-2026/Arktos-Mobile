// SPDX-License-Identifier: MPL-2.0
package com.arktosbrowser.mobile.download

import android.content.Context
import com.arktosbrowser.mobile.di.injector
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * Actions reachable from inside the generated downloads page — the
 * page links to [CLEAR_URL], both engines intercept it, wipe the
 * download list, then reload the page.
 */
object DownloadsPageActions {
    const val CLEAR_URL = "arktos://downloads/clear"

    fun clearAll(context: Context, onDone: () -> Unit) {
        Completable.fromAction {
            context.injector.downloadsRepository().deleteAllDownloads().blockingAwait()
        }
            .subscribeOn(Schedulers.io())
            .subscribe { onDone() }
    }
}
