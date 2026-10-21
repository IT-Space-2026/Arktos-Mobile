// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.adblock.source

import io.reactivex.rxjava3.core.Single

/**
 * A data source that contains hosts.
 */
interface HostsDataSource {

    /**
     * Load the hosts and emit them as a [Single] [HostsResult].
     */
    fun loadHosts(): Single<HostsResult>

}
