package com.arktosbrowser.mobile.rust

import com.arktosbrowser.mobile.database.DatabaseKeyProvider

/** SEC15 — fixed 32-byte SQLCipher page key for host-side store tests. */
internal val TEST_STORE_KEY = ByteArray(DatabaseKeyProvider.KEY_BYTES) { 0x5A }
