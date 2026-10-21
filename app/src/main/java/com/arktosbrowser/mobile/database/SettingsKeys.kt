// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.database

sealed class SettingsKeys(
        open val key: String,
        open val value: String
)