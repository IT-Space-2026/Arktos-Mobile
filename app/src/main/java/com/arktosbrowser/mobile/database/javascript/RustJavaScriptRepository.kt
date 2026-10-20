package com.arktosbrowser.mobile.database.javascript

import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.StoreJavaScriptEntry
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [JavaScriptRepository] backed by the Rust storage core (STORE01).
 * Replaces `JavaScriptDatabase`.
 */
@Singleton
class RustJavaScriptRepository @Inject constructor(
    private val store: ArktosStore
) : JavaScriptRepository {

    override fun deleteJavaScript(): Completable = Completable.fromAction {
        store.deleteAllJavascript()
    }

    override fun deleteJavaScriptEntry(name: String): Completable = Completable.fromAction {
        store.deleteJavascriptEntry(name)
    }

    override fun findJavaScriptEntriesContaining(query: String): Single<List<JavaScriptEntry>> =
        Single.fromCallable {
            store.findJavascriptEntriesContaining(query).map { it.toJavaScriptEntry() }
        }

    override fun lastHundredVisitedJavaScriptEntries(): Single<List<JavaScriptEntry>> =
        Single.fromCallable {
            store.lastHundredJavascriptEntries().map { it.toJavaScriptEntry() }
        }

    override fun addJavaScriptIfNotExists(entry: JavaScriptEntry): Single<Boolean> =
        Single.fromCallable {
            store.addJavascriptIfNotExists(entry.toStoreEntry())
        }
}

internal fun JavaScriptEntry.toStoreEntry() = StoreJavaScriptEntry(
    name = name,
    namespace = namespace,
    version = version,
    author = author,
    include = include,
    exclude = exclude,
    time = time,
    permissions = permissions,
    code = code,
    requirements = requirements,
    runAt = runAt,
    noFrames = noFrames
)

internal fun StoreJavaScriptEntry.toJavaScriptEntry() = JavaScriptEntry(
    name = name,
    namespace = namespace,
    version = version,
    author = author,
    include = include,
    exclude = exclude,
    time = time,
    permissions = permissions,
    code = code,
    requirements = requirements,
    runAt = runAt,
    noFrames = noFrames
)
