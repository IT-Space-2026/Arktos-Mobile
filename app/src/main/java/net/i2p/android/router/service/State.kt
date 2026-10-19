/*
 * Copyright 2014-2017 str4d and the I2P Android project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Vendored from i2p.android.base (net.i2p.android:helper:0.9.5) — the
 * package name is part of the AIDL wire contract and must not change.
 */

package net.i2p.android.router.service

import android.os.Parcel
import android.os.Parcelable
import android.util.Log

/** Extracted from RouterService. */
enum class State : Parcelable {
    // These states persist even if we died... Yuck, it causes issues.
    INIT, WAITING, STARTING, RUNNING, ACTIVE,

    // unplanned (router stopped itself), next: killSelf()
    STOPPING, STOPPED,

    // button, don't kill service when stopped, stay in MANUAL_STOPPED
    MANUAL_STOPPING, MANUAL_STOPPED,

    // button, DO kill service when stopped, next: killSelf()
    MANUAL_QUITTING, MANUAL_QUITTED,

    // Stopped by listener (no network), next: WAITING (spin for network)
    NETWORK_STOPPING, NETWORK_STOPPED,

    GRACEFUL_SHUTDOWN;

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
    }

    companion object {
        private const val TAG = "I2P"

        @JvmField
        val CREATOR = object : Parcelable.Creator<State> {
            override fun createFromParcel(source: Parcel): State? = try {
                val stateVal = source.readString()
                if (stateVal == null) {
                    Log.e(TAG, "Received null from State Parcel.")
                    null
                } else {
                    valueOf(stateVal)
                }
            } catch (e: IllegalArgumentException) {
                // Parcel is from a newer version of State with new states.
                null
            }

            override fun newArray(size: Int): Array<State?> = arrayOfNulls(size)
        }
    }
}
