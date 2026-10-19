package com.arktosbrowser.mobile.download

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Resolves the on-disk file name for a download.
 *
 * Replaces `URLUtil.guessFileName`, which produced `*.bin` files whenever the
 * URL path carried no extension and the mime type was unknown
 * (application/octet-stream), and which truncated multi-dot file names at the
 * first '.' (upstream SmartCookieWeb #311). Pure JVM so it is unit-testable
 * and can be reused by a future non-WebView download core.
 */
object DownloadNaming {

    /**
     * Resolve a safe, correctly-extended file name from, in order:
     * Content-Disposition (RFC 6266 `filename*`/`filename`), the URL path,
     * then a synthesized `download.<ext>` name.
     *
     * [mimeType] should be the server-reported Content-Type when known
     * (see [DownloadHttpProbe]); the WebView-reported guess is a last resort.
     */
    fun resolveFileName(url: String?, contentDisposition: String?, mimeType: String?): String {
        fileNameFromContentDisposition(contentDisposition)?.let { name ->
            if (extensionFromFileName(name) != null) {
                return sanitizeFileName(name)
            }
            return sanitizeFileName(name + extensionForMimeType(mimeType))
        }

        val urlName = fileNameFromUrl(url)
        if (urlName != null) {
            if (extensionFromFileName(urlName) != null) {
                return sanitizeFileName(urlName)
            }
            return sanitizeFileName(urlName + extensionForMimeType(mimeType))
        }

        return "download" + extensionForMimeType(mimeType)
    }

    /**
     * Best-effort filename from a Content-Disposition header. Handles both
     * `filename=` and RFC 5987 `filename*=` (which URLUtil misses entirely);
     * returns `null` when absent or malformed.
     */
    fun fileNameFromContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition == null ||
            !contentDisposition.lowercase().contains("filename")
        ) {
            return null
        }
        val raw = try {
            ContentDispositionFileNameParser.parse(contentDisposition)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        if (raw.none { it.isLetterOrDigit() }) return null
        return try {
            // Plain (non-extended) filename params are historically latin-1.
            URLDecoder.decode(raw, StandardCharsets.ISO_8859_1.name())
        } catch (e: Exception) {
            raw
        }
    }

    /**
     * Last path segment of [url], percent-decoded, with query and fragment
     * stripped. `null` when the URL yields no usable name.
     */
    fun fileNameFromUrl(url: String?): String? {
        val decoded = try {
            URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            url
        } ?: return null
        val path = decoded.substringBefore('?').substringBefore('#')
        val segment = path.substringAfterLast('/')
        return segment.takeIf { it.isNotBlank() }
    }

    /**
     * Extension (with leading dot, lower-cased) of a file name, or `null`.
     * Uses the LAST dot; rejects leading dots (`".profile"`), empty tails and
     * implausibly long candidates (URLUtil splits at the FIRST dot, mangling
     * names like `app-1.2.3.apk`).
     */
    fun extensionFromFileName(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        val ext = fileName.substring(dot + 1)
        if (ext.length > 6 || ext.any { it == '/' || it == '\\' || it == ' ' }) return null
        return ".$ext".lowercase()
    }

    /**
     * File extension for a Content-Type, or `.bin` when it is binary and
     * unidentifiable. Unlike MimeTypeMap this covers the types browsers
     * actually download and never returns `null` — callers always get a
     * complete name.
     */
    fun extensionForMimeType(mimeType: String?): String {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase()
        val mapped = type?.let { MIME_TO_EXTENSION[it] }
        if (mapped != null) return mapped
        return when {
            type == null -> ".bin"
            type == "text/html" || type == "application/xhtml+xml" -> ".html"
            type.startsWith("text/") -> ".txt"
            else -> ".bin"
        }
    }

    /** Sanitize a resolved name against path traversal and separators. */
    fun sanitizeFileName(name: String?): String {
        val cleaned = name.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("..", "")
            .trim()
        return cleaned.ifBlank { "download" }
    }

    private val MIME_TO_EXTENSION = mapOf(
        "application/vnd.android.package-archive" to ".apk",
        "application/zip" to ".zip",
        "application/x-zip-compressed" to ".zip",
        "application/gzip" to ".gz",
        "application/x-gzip" to ".gz",
        "application/x-tar" to ".tar",
        "application/x-bzip2" to ".bz2",
        "application/x-7z-compressed" to ".7z",
        "application/x-rar-compressed" to ".rar",
        "application/vnd.rar" to ".rar",
        "application/pdf" to ".pdf",
        "application/json" to ".json",
        "application/xml" to ".xml",
        "application/x-bittorrent" to ".torrent",
        "application/epub+zip" to ".epub",
        "application/msword" to ".doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ".docx",
        "application/vnd.ms-excel" to ".xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to ".xlsx",
        "application/vnd.ms-powerpoint" to ".ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to ".pptx",
        "application/vnd.oasis.opendocument.text" to ".odt",
        "application/vnd.oasis.opendocument.spreadsheet" to ".ods",
        "application/x-iso9660-image" to ".iso",
        "application/x-apple-diskimage" to ".dmg",
        "application/x-debian-package" to ".deb",
        "application/x-rpm" to ".rpm",
        "application/x-msdownload" to ".exe",
        "application/x-msi" to ".msi",
        "application/wasm" to ".wasm",
        "image/jpeg" to ".jpg",
        "image/png" to ".png",
        "image/gif" to ".gif",
        "image/webp" to ".webp",
        "image/svg+xml" to ".svg",
        "image/bmp" to ".bmp",
        "image/x-icon" to ".ico",
        "audio/mpeg" to ".mp3",
        "audio/mp4" to ".m4a",
        "audio/ogg" to ".ogg",
        "audio/wav" to ".wav",
        "audio/x-wav" to ".wav",
        "audio/flac" to ".flac",
        "video/mp4" to ".mp4",
        "video/mpeg" to ".mpg",
        "video/webm" to ".webm",
        "video/x-matroska" to ".mkv",
        "video/x-msvideo" to ".avi",
        "video/quicktime" to ".mov",
        "font/ttf" to ".ttf",
        "font/otf" to ".otf",
        "font/woff" to ".woff",
        "font/woff2" to ".woff2"
    )
}
