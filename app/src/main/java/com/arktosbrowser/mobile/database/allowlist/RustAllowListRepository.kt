package com.arktosbrowser.mobile.database.allowlist

import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.StoreAllowListEntry
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AdBlockAllowListRepository] backed by the Rust storage core
 * (STORE01). Replaces `AdBlockAllowListDatabase`.
 */
@Singleton
class RustAllowListRepository @Inject constructor(
    private val store: ArktosStore
) : AdBlockAllowListRepository {

    override fun allAllowListItems(): Single<List<AllowListEntry>> = Single.fromCallable {
        store.allAllowlistItems().map { it.toAllowListEntry() }
    }

    override fun allowListItemForUrl(url: String): Maybe<AllowListEntry> = Maybe.fromCallable {
        store.allowlistItemForUrl(url)?.toAllowListEntry()
    }

    override fun addAllowListItem(whitelistItem: AllowListEntry): Completable =
        Completable.fromAction {
            store.addAllowlistItem(whitelistItem.domain, whitelistItem.timeCreated.toULong())
        }

    override fun removeAllowListItem(whitelistItem: AllowListEntry): Completable =
        Completable.fromAction {
            store.removeAllowlistItem(whitelistItem.domain)
        }

    override fun clearAllowList(): Completable = Completable.fromAction {
        store.clearAllowlist()
    }
}

internal fun StoreAllowListEntry.toAllowListEntry() = AllowListEntry(
    domain = domain,
    timeCreated = timeCreated.toLong()
)
