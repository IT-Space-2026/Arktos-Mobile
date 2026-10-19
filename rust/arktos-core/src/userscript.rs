//! Userscript metadata parsing + injection prep (PORT01).
//!
//! Port of `ArktosCookieWebClient.installExtension` / `parseLine` /
//! `urlToPattern` / `urlToParsedPattern`: given the raw script text
//! (as pulled out of `document.body.innerText` via
//! `evaluateJavascript`), extract the `==UserScript==` metadata block,
//! turn `@include`/`@match` rules into Java-regex pattern strings, and
//! hand back the script body with the metadata block removed.
//!
//! The emitted include patterns are *regex strings* — the Kotlin side
//! still compile-checks them with `java.util.regex` before storing
//! (dropping malformed ones, same as upstream's `Pattern.compile`
//! try/catch) and later matches URLs against them with `toRegex()`.
//!
//! Deliberately preserved upstream quirks:
//! * `installExtension` kept scanning `// @field` lines past the
//!   `==/UserScript==` terminator (`return@forEachLine` continued the
//!   loop rather than breaking).
//! * `urlToPattern`'s escaping `.replace(...)` chain was applied to a
//!   throwaway copy — the stored pattern is the raw `@include` value
//!   wrapped in `^`/`^\w+://`, *not* an escaped regex.
//! * The script body (`code`) is cut from the *original* source, while
//!   metadata fields are read from the unescaped text.

/// Parsed `==UserScript==` block + prepared body, as stored into a
/// `JavaScriptEntry` row.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct UserScriptMeta {
    /// `@name` — required upstream (`name!!`); `None` when absent.
    pub name: Option<String>,
    /// `@version`.
    pub version: Option<String>,
    /// `@author`.
    pub author: Option<String>,
    /// `@description` — parsed upstream but never stored; kept for
    /// future metadata display.
    pub description: Option<String>,
    /// Candidate URL regexes from `@include`/`@match`, in order.
    /// Kotlin compile-checks each before persisting.
    pub includes: Vec<String>,
    /// `@require` URLs, in order.
    pub requires: Vec<String>,
    /// Script body with the metadata block removed (empty when the
    /// source has no `==UserScript==` block — upstream quirk).
    pub code: String,
    /// Whether the first line opened `==UserScript==`; upstream logged
    /// a parse error when false but continued anyway.
    pub has_metadata_header: bool,
}

/// Parse a userscript source into metadata + body. Never fails —
/// malformed input yields whatever fields matched, exactly like the
/// line-oriented Kotlin parser.
#[uniffi::export]
pub fn parse_userscript(source: String) -> UserScriptMeta {
    let unescaped = unescape_js_text(&source);

    let mut meta = UserScriptMeta {
        name: None,
        version: None,
        author: None,
        description: None,
        includes: Vec::new(),
        requires: Vec::new(),
        code: strip_metadata_block(&source),
        has_metadata_header: false,
    };

    let mut lines = unescaped.lines();
    meta.has_metadata_header = lines
        .next()
        .map(is_metadata_start)
        .unwrap_or(false);

    for line in lines {
        if let Some((field, value)) = metadata_field(line) {
            apply_field(&mut meta, field, value);
        }
        // Non-metadata lines (including the `==/UserScript==`
        // terminator) are simply skipped — upstream kept iterating.
    }

    meta
}

/// The `installExtension` unescape chain — `document.body.innerText`
/// arrives wrapped in a JS string literal whose escapes get partially
/// unescaped before parsing.
fn unescape_js_text(text: &str) -> String {
    text.replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "")
        .replace("\\u003C", "<")
        .replace("/\"", "\"")
        .replace("//\"", "/\"")
        .replace("\\\\'", "\\'")
        .replace("\\\\\"", "\\\"")
}

/// `\s*//\s*==UserScript==\s*` (case-insensitive), whole line.
fn is_metadata_start(line: &str) -> bool {
    let trimmed = line.trim();
    let body = trimmed
        .strip_prefix("//")
        .map(str::trim_start)
        .unwrap_or(trimmed);
    body.eq_ignore_ascii_case("==UserScript==")
}

/// `\s*//\s*@(\S+)(?:\s+(.*))?` (case-insensitive). Returns the field
/// name (as written, lowercased by the caller comparison) and the
/// optional value.
fn metadata_field(line: &str) -> Option<(&str, Option<&str>)> {
    let trimmed = line.trim_start();
    let rest = trimmed.strip_prefix("//")?.trim_start();
    let rest = rest.strip_prefix('@')?;
    let end = rest
        .find(|c: char| c.is_whitespace())
        .unwrap_or(rest.len());
    if end == 0 {
        return None;
    }
    let field = &rest[..end];
    // `(?:\s+(.*))?` — value is everything after the whitespace run,
    // `Some("")` when the line ends right after the run, `None` only
    // when the line ends at the field name.
    let after = &rest[end..];
    let value = if after.is_empty() {
        None
    } else {
        Some(after.trim_start_matches(|c: char| c.is_whitespace()))
    };
    Some((field, value))
}

fn apply_field(meta: &mut UserScriptMeta, field: &str, value: Option<&str>) {
    if field.eq_ignore_ascii_case("name") {
        meta.name = value.map(str::to_owned);
    } else if field.eq_ignore_ascii_case("version") {
        meta.version = value.map(str::to_owned);
    } else if field.eq_ignore_ascii_case("author") {
        meta.author = value.map(str::to_owned);
    } else if field.eq_ignore_ascii_case("description") {
        meta.description = value.map(str::to_owned);
    } else if field.eq_ignore_ascii_case("require") {
        if let Some(v) = value {
            meta.requires.push(v.to_owned());
        }
    } else if field.eq_ignore_ascii_case("include") {
        if let Some(pattern) = url_to_pattern(value) {
            meta.includes.push(pattern);
        }
    } else if field.eq_ignore_ascii_case("match") {
        if let Some(pattern) = match_to_pattern(value) {
            meta.includes.push(pattern);
        }
    }
}

/// Port of `urlToPattern`: the escaping replaces upstream applied to
/// a discarded `StringBuilder.toString()` copy, so the raw pattern
/// text survives — `.tld` rewrite, then `^` or `^\w+://` prefix.
fn url_to_pattern(pattern_url: Option<&str>) -> Option<String> {
    let converted = apply_tld_rewrite(pattern_url?);
    if starts_with_scheme(&converted) {
        Some(format!("^{converted}"))
    } else {
        Some(format!("^\\w+://{converted}"))
    }
}

/// Port of the `@match` transform: escape `?`/`.`, expand `*`/`+`,
/// widen `://*.` subdomain wildcards, normalise a `*://` scheme, then
/// `.tld` rewrite (via `urlToParsedPattern`).
fn match_to_pattern(value: Option<&str>) -> Option<String> {
    let transformed = value?
        .replace('?', "\\?")
        .replace('.', "\\.")
        .replace('*', ".*")
        .replace('+', ".+")
        .replace("://.*\\.", "://((?![\\./]).)*\\.");
    let transformed = transformed
        .strip_prefix(".*://")
        .map(|rest| format!("https?://{rest}"))
        .unwrap_or(transformed);
    Some(apply_tld_rewrite(&format!("^{transformed}")))
}

/// `^\w+:` (case-insensitive `find` = anchored match) — does the
/// pattern start with a `scheme:` prefix?
fn starts_with_scheme(pattern: &str) -> bool {
    let head: String = pattern.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_').collect();
    !head.is_empty() && pattern[head.len()..].starts_with(':')
}

/// Kotlin `TLD_REGEX.replaceFirst(s, "$1(.[a-z]{1,6}){1,3}$2")` with
/// `^([^:]+://[^/]+)\.tld(/.*)?$`: rewrite the first `host.tld`
/// occurrence — but only when it closes the host and is followed by a
/// path or end-of-pattern. Anything else is left untouched.
fn apply_tld_rewrite(pattern: &str) -> String {
    // `.tld` lookup is case-insensitive upstream (contains(".tld",
    // true) gate), but the regex itself is case-sensitive — a `.TLD`
    // pattern was gated in and then not rewritten. Only lowercase
    // hits rewrite here.
    let Some(idx) = pattern.find(".tld") else {
        return pattern.to_owned();
    };
    let before = &pattern[..idx];
    let after = &pattern[idx + 4..];
    let Some(scheme_end) = before.find("://") else {
        return pattern.to_owned();
    };
    let (scheme, host) = (&before[..scheme_end], &before[scheme_end + 3..]);
    if scheme.is_empty() || scheme.contains(':') || host.is_empty() || host.contains('/') {
        return pattern.to_owned();
    }
    if !(after.is_empty() || after.starts_with('/')) {
        return pattern.to_owned();
    }
    format!("{before}(.[a-z]{{1,6}}){{1,3}}{after}")
}

/// `metadataRegex = ==UserScript==(.*?)==/UserScript==` DOTALL on the
/// original source: remove every occurrence of the first minimal
/// match (`String.replace` is literal-replace-all upstream). No block
/// → empty body, matching upstream's `var code = ""` fallback.
fn strip_metadata_block(source: &str) -> String {
    let Some(start) = source.find("==UserScript==") else {
        return String::new();
    };
    let rest = &source[start + "==UserScript==".len()..];
    let Some(end_rel) = rest.find("==/UserScript==") else {
        return String::new();
    };
    let block = &source[start..start + "==UserScript==".len() + end_rel + "==/UserScript==".len()];
    source.replace(block, "")
}

#[cfg(test)]
mod tests {
    use super::*;

    const SCRIPT: &str = "\
// ==UserScript==
// @name        Test Script
// @version     1.2.3
// @author      Jane
// @description does things
// @require     https://cdn.example.com/lib.js
// @include     example.com/*
// @match       https://*.example.org/*
// ==/UserScript==
console.log('hi');";

    #[test]
    fn parses_full_metadata_block() {
        let meta = parse_userscript(SCRIPT.to_owned());
        assert!(meta.has_metadata_header);
        assert_eq!(meta.name.as_deref(), Some("Test Script"));
        assert_eq!(meta.version.as_deref(), Some("1.2.3"));
        assert_eq!(meta.author.as_deref(), Some("Jane"));
        assert_eq!(meta.description.as_deref(), Some("does things"));
        assert_eq!(meta.requires, vec!["https://cdn.example.com/lib.js"]);
        // The `// ` prefix on the first line survives — the metadata
        // regex starts at `==UserScript==`, not at the line start.
        assert_eq!(meta.code, "// \nconsole.log('hi');");
    }

    #[test]
    fn include_gets_scheme_prefix_match_gets_regex_transform() {
        let meta = parse_userscript(SCRIPT.to_owned());
        assert_eq!(meta.includes[0], "^\\w+://example.com/*");
        // `.` escaped, `*` widened — and `*.` already widened by the
        // `://.*\.` subdomain rule.
        assert_eq!(
            meta.includes[1],
            "^https://((?![\\./]).)*\\.example\\.org/.*"
        );
    }

    #[test]
    fn include_with_scheme_keeps_it() {
        let meta =
            parse_userscript("// ==UserScript==\n// @include https://a.com/*\nx();".to_owned());
        assert_eq!(meta.includes, vec!["^https://a.com/*"]);
    }

    #[test]
    fn tld_include_is_rewritten_only_with_scheme() {
        // Upstream's TLD regex requires `scheme://` before the host.
        let meta = parse_userscript(
            "// ==UserScript==\n// @include http://example.tld/*\nx();".to_owned(),
        );
        assert_eq!(
            meta.includes,
            vec!["^http://example(.[a-z]{1,6}){1,3}/*"]
        );
        // Without a scheme it passes through untouched.
        let meta = parse_userscript(
            "// ==UserScript==\n// @include example.tld/*\nx();".to_owned(),
        );
        assert_eq!(meta.includes, vec!["^\\w+://example.tld/*"]);
    }

    #[test]
    fn missing_metadata_block_yields_empty_code() {
        let meta = parse_userscript("console.log('x');".to_owned());
        assert!(!meta.has_metadata_header);
        assert!(meta.name.is_none());
        assert_eq!(meta.code, "");
    }

    #[test]
    fn unescapes_js_string_wrapping() {
        let src = "// ==UserScript==\\n// @name Escaped\\n// ==/UserScript==\\nalert(\\\"x\\\");";
        let meta = parse_userscript(src.to_owned());
        assert!(meta.has_metadata_header);
        assert_eq!(meta.name.as_deref(), Some("Escaped"));
    }

    #[test]
    fn metadata_lines_after_terminator_still_parse() {
        // Upstream quirk: the loop continued past ==/UserScript==.
        let src = "// ==UserScript==\n// ==/UserScript==\n// @name Late\nx();";
        let meta = parse_userscript(src.to_owned());
        assert_eq!(meta.name.as_deref(), Some("Late"));
    }
}
