// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.adblock.source

/**
 * The result of a request for the hosts file content.
 */
sealed class HostsResult {

    /**
     * A successful request.
     *
     * @param hostsFileContent The raw hosts file content. Parsing into
     * blocklist entries happens in the Rust core — see
     * `BlocklistLoader`/`parse_hosts_file`.
     */
    data class Success(val hostsFileContent: String) : HostsResult()

    /**
     * An unsuccessful request.
     *
     * @param cause The cause of the failure.
     */
    data class Failure(val cause: Exception) : HostsResult()

}
