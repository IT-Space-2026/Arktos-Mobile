package com.arktosbrowser.mobile.database

sealed class SettingsKeys(
        open val key: String,
        open val value: String
)