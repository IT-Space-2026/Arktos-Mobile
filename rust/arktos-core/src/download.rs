// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
//! Download engine core (PORT01).
//!
//! One Rust home for the filename/MIME logic that used to be spread
//! across `DownloadHandler`, `ContentDispositionFileNameParser`,
//! `DownloadUtil` and `FetchUrlMimeType` — the parts upstream
//! SmartCookieWeb issues #270/#311/#303 are about:
//!
//! * `content_disposition_filename` — RFC 6266/5987 parser (port of
//!   the Spring-derived Kotlin object), `filename*` support included.
//! * `download_file_name` — port of `DownloadHandler.getFileNameFromURL`
//!   *plus* the core-level fix: upstream gated on
//!   `contains("filename=")` which never fires for a `filename*`-only
//!   header; we gate on `filename` so RFC 5987 names are honored.
//! * `guess_file_name` — `URLUtil.guessFileName` parity for the
//!   `DownloadManager` request path.
//! * `sanitize_file_name` — path-traversal scrubbing.
//! * `mime_type_for_extension` — `MimeTypeMap.getMimeTypeFromExtension`
//!   equivalent via `mime_guess`, for the `FetchUrlMimeType`
//!   text/plain→real-type correction.
//! * `format_data_size` — `DownloadUtil.getDataSize` parity.
//!
//! Malformed headers return `None` instead of throwing — upstream let
//! `IllegalArgumentException` escape into the download listener, which
//! crashed the download flow rather than falling back to a guessed
//! name.

/// Parse the `filename`/`filename*` parameter out of a
/// Content-Disposition header (RFC 2183 tokenization + RFC 5987
/// decoding). `None` when absent or malformed.
#[uniffi::export]
pub fn content_disposition_filename(content_disposition: String) -> Option<String> {
    parse_content_disposition(&content_disposition).ok().flatten()
}

/// Sanitize a proposed file name: strip directory components, remove
/// `..` traversal, fall back to `download`.
#[uniffi::export]
pub fn sanitize_file_name(name: Option<String>) -> String {
    let cleaned = name
        .unwrap_or_default()
        .rsplit('/')
        .next()
        .unwrap_or_default()
        .rsplit('\\')
        .next()
        .unwrap_or_default()
        .replace("..", "");
    let cleaned = cleaned.trim_matches(|c: char| c <= ' ');
    if cleaned.is_empty() {
        "download".to_owned()
    } else {
        cleaned.to_owned()
    }
}

/// `Utils.guessFileExtension` (PORT02) — the substring after the last
/// `.`, `None` when absent or trailing.
#[uniffi::export]
pub fn file_extension(filename: String) -> Option<String> {
    let idx = filename.rfind('.')? + 1;
    if filename.len() > idx {
        Some(filename[idx..].to_owned())
    } else {
        None
    }
}

/// `DownloadHandler.getFileNameFromURL` — pick the file name for a
/// download from the Content-Disposition header when present,
/// otherwise from the URL path, otherwise guess from the MIME type.
///
/// Core-level fix vs upstream: the gate is `filename` (covers both
/// `filename=` and `filename*=`), not `filename=` — a `filename*`-only
/// header used to be ignored entirely.
#[uniffi::export]
pub fn download_file_name(
    url: String,
    content_disposition: Option<String>,
    mime_type: Option<String>,
) -> String {
    let header_name = content_disposition
        .as_deref()
        .filter(|cd| cd.contains("filename"))
        .and_then(|cd| content_disposition_filename(cd.to_owned()));
    if let Some(name) = header_name {
        return sanitize_file_name(Some(url_decode_latin1(&name)));
    }

    let segment = url[url.rfind('/').map(|i| i + 1).unwrap_or(0)..]
        .split('?')
        .next()
        .unwrap_or_default()
        .split('#')
        .next()
        .unwrap_or_default();
    if segment.contains('.') {
        sanitize_file_name(Some(segment.to_owned()))
    } else {
        sanitize_file_name(Some(guess_file_name(url, content_disposition, mime_type)))
    }
}

/// `URLUtil.guessFileName` parity: Content-Disposition first, then the
/// decoded URL path, then a generic name; a missing extension is
/// filled in from the MIME type.
#[uniffi::export]
pub fn guess_file_name(
    url: String,
    content_disposition: Option<String>,
    mime_type: Option<String>,
) -> String {
    let mut filename = content_disposition.and_then(content_disposition_filename);

    if filename.is_none() {
        let decoded = uri_decode(&url);
        let decoded = decoded.split('?').next().unwrap_or_default();
        if !decoded.is_empty() && !decoded.ends_with('/') {
            if let Some(idx) = decoded.rfind('/') {
                filename = Some(decoded[idx + 1..].to_owned());
            }
        }
    }

    let mut filename = filename.unwrap_or_else(|| "downloadfile.bin".to_owned());

    if !filename.contains('.') {
        let extension = mime_type
            .as_deref()
            .and_then(extension_from_mime)
            .map(|e| format!(".{e}"))
            .unwrap_or_else(|| match mime_type.as_deref() {
                Some(m) if m.to_ascii_lowercase().starts_with("text/") => {
                    if m.eq_ignore_ascii_case("text/html") {
                        ".html".to_owned()
                    } else {
                        ".txt".to_owned()
                    }
                }
                _ => ".bin".to_owned(),
            });
        filename.push_str(&extension);
    }
    filename
}

/// Extension → MIME type via `mime_guess`, for the `FetchUrlMimeType`
/// correction of `text/plain`/`application/octet-stream` responses.
/// `extension` is a bare extension (no dot), like
/// `Utils.guessFileExtension` produces.
#[uniffi::export]
pub fn mime_type_for_extension(extension: String) -> Option<String> {
    mime_guess::from_ext(extension.trim_start_matches('.'))
        .first()
        .map(|m| m.essence_str().to_owned())
}

/// MIME type → preferred extension (`MimeTypeMap.getExtensionFromMimeType`
/// equivalent). `mime_guess`'s table is ordered by mime-db (first hit
/// for image/jpeg is "jfif"), so the common types Android's
/// MimeTypeMap answers differently get a curated entry; the rest fall
/// through to mime_guess.
fn extension_from_mime(mime: &str) -> Option<String> {
    let preferred = match mime.to_ascii_lowercase().trim() {
        "image/jpeg" => "jpg",
        "text/plain" => "txt",
        "text/html" => "html",
        "text/css" => "css",
        "text/csv" => "csv",
        "image/png" => "png",
        "image/gif" => "gif",
        "image/webp" => "webp",
        "image/bmp" => "bmp",
        "image/svg+xml" => "svg",
        "audio/mpeg" => "mp3",
        "audio/ogg" => "ogg",
        "audio/x-wav" | "audio/wav" | "audio/vnd.wave" => "wav",
        "audio/mp4" | "audio/x-m4a" => "m4a",
        "audio/flac" | "audio/x-flac" => "flac",
        "video/mp4" => "mp4",
        "video/webm" => "webm",
        "video/3gpp" => "3gp",
        "video/quicktime" => "mov",
        "video/x-matroska" => "mkv",
        "application/pdf" => "pdf",
        "application/zip" => "zip",
        "application/gzip" | "application/x-gzip" => "gz",
        "application/x-7z-compressed" => "7z",
        "application/x-rar-compressed" | "application/vnd.rar" => "rar",
        "application/x-tar" => "tar",
        "application/x-bittorrent" => "torrent",
        "application/epub+zip" => "epub",
        "application/vnd.android.package-archive" => "apk",
        "application/json" => "json",
        "application/xml" | "text/xml" => "xml",
        "application/msword" => "doc",
        "application/vnd.ms-excel" => "xls",
        "application/vnd.ms-powerpoint" => "ppt",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" => "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" => {
            "pptx"
        }
        "application/octet-stream" => "bin",
        _ => {
            return mime_guess::get_mime_extensions_str(mime)
                .and_then(|list| list.first())
                .map(|s| s.to_string())
        }
    };
    Some(preferred.to_owned())
}

/// `DownloadUtil.getDataSize` — human-readable byte count.
#[uniffi::export]
pub fn format_data_size(size: i64) -> String {
    let size = size.max(0) as f64;
    if size < 1024.0 {
        format!("{}bytes", size as i64)
    } else if size < 1024.0 * 1024.0 {
        format!("{:.2}KB", size / 1024.0)
    } else if size < 1024.0 * 1024.0 * 1024.0 {
        format!("{:.2}MB", size / 1024.0 / 1024.0)
    } else if size < 1024.0 * 1024.0 * 1024.0 * 1024.0 {
        format!("{:.2}GB", size / 1024.0 / 1024.0 / 1024.0)
    } else {
        "size: error".to_owned()
    }
}

// ---- internals -------------------------------------------------------

#[derive(Clone, Copy)]
enum Encoding {
    Utf8,
    Latin1,
    Ascii,
}

fn encode_char(c: char, enc: Encoding, out: &mut Vec<u8>) {
    match enc {
        Encoding::Utf8 => {
            let mut buf = [0u8; 4];
            out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
        }
        // Java toByteArray replaces unmappable chars with '?'.
        Encoding::Latin1 => out.push(if (c as u32) <= 0xFF { c as u8 } else { b'?' }),
        Encoding::Ascii => out.push(if c.is_ascii() { c as u8 } else { b'?' }),
    }
}

/// RFC 6266 parser — port of `ContentDispositionFileNameParser`.
fn parse_content_disposition(header: &str) -> Result<Option<String>, ()> {
    let parts = tokenize(header)?;
    let mut filename: Option<String> = None;
    for part in &parts[1..] {
        let Some(eq) = part.find('=') else {
            return Err(());
        };
        let attribute = &part[..eq];
        let rest = &part[eq + 1..];
        let value = if rest.starts_with('"') && rest.ends_with('"') && rest.len() >= 2 {
            &rest[1..rest.len() - 1]
        } else {
            rest
        };
        if attribute == "filename*" {
            let idx1 = value.find('\'');
            let idx2 = idx1.and_then(|i| value[i + 1..].find('\'').map(|j| i + 1 + j));
            filename = Some(match (idx1, idx2) {
                (Some(i1), Some(i2)) => {
                    let charset = parse_charset(value[..i1].trim_matches(|c: char| c <= ' '))?;
                    decode_filename(&value[i2 + 1..], charset)?
                }
                // US-ASCII when no charset'lang' prefix.
                _ => decode_filename(value, Encoding::Ascii)?,
            });
        } else if attribute == "filename" && filename.is_none() {
            filename = Some(value.to_owned());
        }
    }
    Ok(filename)
}

fn parse_charset(name: &str) -> Result<Encoding, ()> {
    match name.to_ascii_lowercase().as_str() {
        "utf-8" | "utf8" => Ok(Encoding::Utf8),
        "iso-8859-1" | "iso_8859_1" | "iso8859-1" | "iso8859_1" | "8859_1" | "latin1" | "l1"
        | "cp819" => Ok(Encoding::Latin1),
        _ => Err(()),
    }
}

/// `tokenize` — split on `;` while honoring quoted strings and
/// backslash escapes. First element is the disposition type.
fn tokenize(header: &str) -> Result<Vec<String>, ()> {
    let first_semi = header.find(';');
    let type_part = match first_semi {
        Some(i) => &header[..i],
        None => header,
    }
    .trim_matches(|c: char| c <= ' ');
    if type_part.is_empty() {
        return Err(());
    }
    let mut parts = vec![type_part.to_owned()];
    if let Some(mut index) = first_semi {
        while index < header.len() {
            let mut next = index + 1;
            let mut quoted = false;
            let mut escaped = false;
            while next < header.len() {
                let ch = header.as_bytes()[next];
                if ch == b';' {
                    if !quoted {
                        break;
                    }
                } else if !escaped && ch == b'"' {
                    quoted = !quoted;
                }
                escaped = !escaped && ch == b'\\';
                next += 1;
            }
            let part = header[index + 1..next].trim_matches(|c: char| c <= ' ');
            if !part.is_empty() {
                parts.push(part.to_owned());
            }
            index = next;
        }
    }
    Ok(parts)
}

/// RFC 5987 attr-char decoding (`%XX` → byte; non-attr-char → error),
/// then decode the bytes with the declared charset.
fn decode_filename(value: &str, enc: Encoding) -> Result<String, ()> {
    let mut bytes = Vec::with_capacity(value.len());
    for c in value.chars() {
        encode_char(c, enc, &mut bytes);
    }
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if is_rfc5987_attr_char(b) {
            out.push(b);
            i += 1;
        } else if b == b'%' && i + 2 < bytes.len() {
            let hi = (bytes[i + 1] as char).to_digit(16).ok_or(())?;
            let lo = (bytes[i + 2] as char).to_digit(16).ok_or(())?;
            out.push((hi * 16 + lo) as u8);
            i += 3;
        } else {
            return Err(());
        }
    }
    Ok(match enc {
        Encoding::Utf8 => String::from_utf8_lossy(&out).into_owned(),
        Encoding::Latin1 | Encoding::Ascii => out
            .iter()
            .map(|&b| {
                if matches!(enc, Encoding::Ascii) && b > 0x7F {
                    '?'
                } else {
                    b as char
                }
            })
            .collect(),
    })
}

fn is_rfc5987_attr_char(c: u8) -> bool {
    matches!(c,
        b'0'..=b'9' | b'a'..=b'z' | b'A'..=b'Z' |
        b'!' | b'#' | b'$' | b'&' | b'+' | b'-' | b'.' | b'^' | b'_' | b'`' | b'|' | b'~')
}

/// `URLDecoder.decode(s, "ISO-8859-1")` — `+`→space, `%XX`→byte→char.
/// Malformed escapes pass through literally (upstream threw).
fn url_decode_latin1(value: &str) -> String {
    let bytes: Vec<u8> = value
        .chars()
        .map(|c| if (c as u32) <= 0xFF { c as u8 } else { b'?' })
        .collect();
    let mut out = String::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => {
                out.push(' ');
                i += 1;
            }
            b'%' if i + 2 < bytes.len() => {
                match (
                    (bytes[i + 1] as char).to_digit(16),
                    (bytes[i + 2] as char).to_digit(16),
                ) {
                    (Some(hi), Some(lo)) => {
                        out.push((hi * 16 + lo) as u8 as char);
                        i += 3;
                    }
                    _ => {
                        out.push('%');
                        i += 1;
                    }
                }
            }
            b => {
                out.push(b as char);
                i += 1;
            }
        }
    }
    out
}

/// `Uri.decode` — `%XX`→UTF-8 bytes; `+` stays literal; malformed
/// escapes pass through.
fn uri_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            match (
                (bytes[i + 1] as char).to_digit(16),
                (bytes[i + 2] as char).to_digit(16),
            ) {
                (Some(hi), Some(lo)) => {
                    out.push((hi * 16 + lo) as u8);
                    i += 3;
                    continue;
                }
                _ => {}
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- content-disposition ------------------------------------

    #[test]
    fn parses_plain_filename() {
        assert_eq!(
            content_disposition_filename("attachment; filename=\"report.pdf\"".into()),
            Some("report.pdf".to_owned())
        );
    }

    #[test]
    fn parses_unquoted_filename() {
        assert_eq!(
            content_disposition_filename("attachment; filename=data.bin".into()),
            Some("data.bin".to_owned())
        );
    }

    #[test]
    fn parses_rfc5987_filename_star_utf8() {
        let cd = "attachment; filename*=UTF-8''%E2%82%AC%20rates.pdf";
        assert_eq!(
            content_disposition_filename(cd.into()),
            Some("€ rates.pdf".to_owned())
        );
    }

    #[test]
    fn filename_star_wins_over_plain() {
        let cd = "attachment; filename=plain.txt; filename*=UTF-8''fancy.txt";
        assert_eq!(
            content_disposition_filename(cd.into()),
            Some("fancy.txt".to_owned())
        );
    }

    #[test]
    fn quoted_semicolon_inside_filename_is_not_split() {
        let cd = "attachment; filename=\"a;b.txt\"";
        assert_eq!(
            content_disposition_filename(cd.into()),
            Some("a;b.txt".to_owned())
        );
    }

    #[test]
    fn malformed_headers_return_none() {
        assert_eq!(content_disposition_filename("".into()), None);
        // header with no filename parameter at all → None
        assert_eq!(content_disposition_filename("attachment".into()), None);
        // bad parameter (no '=') → error → None
        assert_eq!(content_disposition_filename("attachment; foo".into()), None);
        // unsupported charset → error → None
        assert_eq!(
            content_disposition_filename("attachment; filename*=UTF-16''x".into()),
            None
        );
    }

    // ---- sanitize ------------------------------------------------

    #[test]
    fn sanitize_strips_traversal_and_dirs() {
        assert_eq!(sanitize_file_name(Some("../../etc/passwd".into())), "passwd");
        assert_eq!(sanitize_file_name(Some("..\\..\\evil.apk".into())), "evil.apk");
        assert_eq!(sanitize_file_name(Some("dir/sub/file.zip".into())), "file.zip");
        assert_eq!(sanitize_file_name(Some("C:\\dir\\file.zip".into())), "file.zip");
        assert!(!sanitize_file_name(Some("a/../../b.txt".into())).contains(".."));
    }

    #[test]
    fn sanitize_defaults_to_download() {
        assert_eq!(sanitize_file_name(None), "download");
        assert_eq!(sanitize_file_name(Some("".into())), "download");
        assert_eq!(sanitize_file_name(Some("   ".into())), "download");
        assert_eq!(sanitize_file_name(Some("../".into())), "download");
    }

    // ---- download_file_name ---------------------------------------

    #[test]
    fn download_name_prefers_header() {
        assert_eq!(
            download_file_name(
                "https://x.com/get?id=1".into(),
                Some("attachment; filename=\"r.pdf\"".into()),
                Some("application/pdf".into()),
            ),
            "r.pdf"
        );
    }

    #[test]
    fn download_name_honors_filename_star_only_header() {
        // Upstream bug: a filename*-only header was ignored.
        assert_eq!(
            download_file_name(
                "https://x.com/dl".into(),
                Some("attachment; filename*=UTF-8''report.pdf".into()),
                None,
            ),
            "report.pdf"
        );
    }

    #[test]
    fn download_name_from_url_segment() {
        assert_eq!(
            download_file_name("https://x.com/a/b/pic.png?x=1#f".into(), None, None),
            "pic.png"
        );
    }

    #[test]
    fn download_name_guesses_extension_from_mime() {
        assert_eq!(
            download_file_name(
                "https://x.com/download".into(),
                None,
                Some("image/jpeg".into()),
            ),
            "download.jpg"
        );
        assert_eq!(
            download_file_name("https://x.com/d".into(), None, Some("text/html".into())),
            "d.html"
        );
        assert_eq!(
            download_file_name("https://x.com/d".into(), None, Some("application/x-thing".into())),
            "d.bin"
        );
    }

    // ---- guess_file_name ------------------------------------------

    #[test]
    fn guess_uses_decoded_url_path() {
        assert_eq!(
            guess_file_name("https://x.com/a%20b.zip?dl=1".into(), None, None),
            "a b.zip"
        );
    }

    #[test]
    fn guess_falls_back_to_generic_name() {
        assert_eq!(
            guess_file_name("https://x.com/".into(), None, None),
            "downloadfile.bin"
        );
    }

    #[test]
    fn guess_appends_mime_extension_when_missing() {
        assert_eq!(
            guess_file_name("https://x.com/d".into(), None, Some("text/plain".into())),
            "d.txt"
        );
        assert_eq!(
            guess_file_name("https://x.com/d".into(), None, Some("image/png".into())),
            "d.png"
        );
    }

    // ---- misc ------------------------------------------------------

    #[test]
    fn mime_lookup_for_extension() {
        assert_eq!(
            mime_type_for_extension("jpg".into()),
            Some("image/jpeg".to_owned())
        );
        assert!(mime_type_for_extension("notanext".into()).is_none());
    }

    #[test]
    fn data_size_formatting() {
        assert_eq!(format_data_size(512), "512bytes");
        assert_eq!(format_data_size(2048), "2.00KB");
        assert_eq!(format_data_size(3 * 1024 * 1024), "3.00MB");
        assert_eq!(format_data_size(2 * 1024 * 1024 * 1024), "2.00GB");
        assert_eq!(format_data_size(-5), "0bytes");
        assert_eq!(format_data_size(i64::MAX), "size: error");
    }
}
