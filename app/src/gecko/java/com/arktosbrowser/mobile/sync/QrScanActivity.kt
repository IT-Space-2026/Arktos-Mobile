// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.sync

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The zxing capture activity locked portrait — the library default
 * opens sideways; declared portrait-only in the gecko manifest.
 */
class QrScanActivity : CaptureActivity()
