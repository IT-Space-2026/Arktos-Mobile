// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.download

/**
 * Risk assessment for a resolved download.
 *
 * Flags file names whose extension is executable or scriptable (the file can
 * run code if opened after download) and file names that disagree with the
 * server-declared Content-Type — a `photo.jpg` that the server claims is an
 * Android package is almost certainly disguised. This extends the FIXB01
 * header-probe pattern: the probe tells us what the server really answered,
 * and this policy decides whether the user should see a warning before the
 * download is enqueued.
 *
 * Pure JVM so it is unit-testable and stays engine-neutral.
 */
object DownloadRisk {

    /** Categories of risk a download can carry. */
    enum class Flag {
        /** Extension is a program/installer (.apk, .exe, .msi, …). */
        EXECUTABLE,

        /** Extension is a script or shell artifact (.js, .bat, .ps1, …). */
        SCRIPTABLE,

        /**
         * Extension and server Content-Type disagree about whether the
         * payload is executable — the file is probably disguised.
         */
        MIME_MISMATCH
    }

    /** Result of [assess]. [isRisky] is true when any flag was raised. */
    class Assessment(
        /** Resolved file-name extension (with dot), or `null`. */
        val extension: String?,
        /** Normalized server Content-Type, or `null`. */
        val mimeType: String?,
        /** Raised flags; empty means the download looks ordinary. */
        val flags: Set<Flag>
    ) {
        val isRisky: Boolean get() = flags.isNotEmpty()
    }

    /**
     * Assess [fileName] (already resolved by [DownloadNaming]) against the
     * effective Content-Type — the probe's answer when available, otherwise
     * the WebView guess.
     */
    fun assess(fileName: String, mimeType: String?): Assessment {
        val extension = DownloadNaming.extensionFromFileName(fileName)
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        val flags = mutableSetOf<Flag>()
        if (extension in EXECUTABLE_EXTENSIONS) flags += Flag.EXECUTABLE
        if (extension in SCRIPTABLE_EXTENSIONS) flags += Flag.SCRIPTABLE
        if (isMimeMismatch(extension, mime)) flags += Flag.MIME_MISMATCH
        return Assessment(extension, mime, flags)
    }

    /**
     * A mismatch exists when the server made a real content-type claim (not a
     * generic binary fallback) that disagrees with the extension about
     * whether the payload can run code. A dangerous mime with no or a benign
     * extension flags (disguised executable); a dangerous extension with a
     * benign mime also flags (server hiding what the file is).
     */
    private fun isMimeMismatch(extension: String?, mime: String?): Boolean {
        if (mime == null || mime in GENERIC_MIME_TYPES) return false
        val extDangerous = extension in EXECUTABLE_EXTENSIONS ||
            extension in SCRIPTABLE_EXTENSIONS
        val mimeDangerous = mime in EXECUTABLE_MIME_TYPES ||
            mime in SCRIPTABLE_MIME_TYPES
        return extDangerous != mimeDangerous
    }

    /** Programs and installers that execute code when opened. */
    private val EXECUTABLE_EXTENSIONS = setOf(
        // Android packages — the real threat on this platform
        ".apk", ".xapk", ".apks", ".apex", ".dex",
        // Windows binaries and installers
        ".exe", ".msi", ".com", ".scr", ".pif", ".cpl", ".dll",
        // Cross-platform installers/packages
        ".jar", ".app", ".dmg", ".pkg", ".deb", ".rpm",
        ".run", ".iso"
    )

    /** Scripts and shell artifacts that run commands or change settings. */
    private val SCRIPTABLE_EXTENSIONS = setOf(
        ".js", ".jse", ".vbs", ".vbe", ".ws", ".wsf", ".wsc", ".wsh",
        ".hta", ".bat", ".cmd", ".ps1", ".ps2", ".msh",
        ".reg", ".inf", ".lnk", ".msc", ".scf", ".url", ".sh"
    )

    /** Content-Types that mean "this payload is a program". */
    private val EXECUTABLE_MIME_TYPES = setOf(
        "application/vnd.android.package-archive",
        "application/vnd.microsoft.portable-executable",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-dosexec",
        "application/x-msi",
        "application/x-ms-installer",
        "application/java-archive",
        "application/x-java-archive",
        "application/x-executable",
        "application/x-sharedlib",
        "application/x-elf",
        "application/x-debian-package",
        "application/x-rpm",
        "application/x-apple-diskimage",
        "application/x-iso9660-image"
    )

    /** Content-Types that mean "this payload is a script". */
    private val SCRIPTABLE_MIME_TYPES = setOf(
        "application/javascript",
        "application/x-javascript",
        "text/javascript",
        "text/jscript",
        "application/x-vbs",
        "text/vbs",
        "text/vbscript",
        "application/x-sh",
        "application/x-shellscript",
        "text/x-sh",
        "application/x-bat",
        "application/x-powershell",
        "text/x-powershell"
    )

    /**
     * Opaque binary types that carry no real claim about the payload — they
     * must not trigger a mismatch, or every generic download would warn.
     */
    private val GENERIC_MIME_TYPES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/force-download",
        "application/x-download",
        "application/download"
    )
}
