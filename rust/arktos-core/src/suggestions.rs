//! Search suggestion core (PORT01).
//!
//! Port of the upstream `*SuggestionsModel` classes: the Rust core owns
//! the query-URL construction and
//! response parsing; the Kotlin shell keeps the actual HTTP fetch
//! (OkHttp + request factory + RxJava threading).
//!
//! Encoding parity:
//! * Google — toolbar XML, decoded as ISO-8859-1
//!   (`parser.setInput(stream, "ISO-8859-1")` upstream), tokenized for
//!   `<suggestion data="...">` via `html5gum` (entity references in the
//!   attribute are resolved like XmlPullParser did).
//! * DuckDuckGo — UTF-8 JSON array of `{"phrase": "..."}` objects.
//! * Startpage — OpenSearch JSON `[query, [suggestions]]` via the
//!   documented `osuggestions` endpoint.
//! * Qwant — UTF-8 JSON `{"data":{"items":[{"value":...}]}}` via the
//!   `api.qwant.com/v3/suggest` endpoint.
//!
//! Malformed responses return an empty list — upstream let the parse
//! exception propagate through RxJava's `onError` which the adapter
//! swallowed, so the visible outcome (no suggestions) is identical.

use html5gum::{Token, Tokenizer};

/// Which suggestion backend produced/should produce the response.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SuggestionProvider {
    Google,
    Duck,
    Startpage,
    Qwant,
}

/// Build the suggestion endpoint URL for an already URL-encoded query.
///
/// `language` is currently unused — upstream dropped the `hl` Google
/// parameter and none of the other providers consume it; kept in the
/// signature so the Kotlin call shape matches `createQueryUrl`.
#[uniffi::export]
pub fn suggestion_query_url(
    provider: SuggestionProvider,
    encoded_query: String,
    _language: String,
) -> String {
    match provider {
        SuggestionProvider::Google => format!(
            "https://suggestqueries.google.com/complete/search?output=toolbar&q={encoded_query}"
        ),
        SuggestionProvider::Duck => format!("https://duckduckgo.com/ac/?q={encoded_query}"),
        SuggestionProvider::Startpage => {
            format!("https://www.startpage.com/osuggestions?q={encoded_query}")
        }
        SuggestionProvider::Qwant => format!(
            "https://api.qwant.com/v3/suggest?q={encoded_query}&locale=en_US&version=2"
        ),
    }
}

/// Parse a provider response body into bare suggestion strings (the
/// Kotlin side wraps them with the localized "suggestion" subtitle).
#[uniffi::export]
pub fn parse_suggestions(provider: SuggestionProvider, body: Vec<u8>) -> Vec<String> {
    match provider {
        SuggestionProvider::Google => parse_google(&body),
        SuggestionProvider::Duck => parse_duck(&body),
        SuggestionProvider::Startpage => parse_startpage(&body),
        SuggestionProvider::Qwant => parse_qwant(&body),
    }
}

/// ISO-8859-1 decode — every byte maps to the code point of the same
/// value.
fn latin1(body: &[u8]) -> String {
    body.iter().map(|&b| b as char).collect()
}

fn parse_google(body: &[u8]) -> Vec<String> {
    let xml = latin1(body);
    let mut out = Vec::new();
    for token in Tokenizer::new(xml.as_bytes()).flatten() {
        if let Token::StartTag(tag) = token {
            if tag.name.eq_ignore_ascii_case(b"suggestion") {
                if let Some((_, attr)) = tag
                    .attributes
                    .iter()
                    .find(|(k, _)| k.as_slice().eq_ignore_ascii_case(b"data"))
                {
                    out.push(String::from_utf8_lossy(&attr.value).into_owned());
                }
            }
        }
    }
    out
}

fn parse_duck(body: &[u8]) -> Vec<String> {
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(body) else {
        return Vec::new();
    };
    value
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.get("phrase").and_then(|p| p.as_str()))
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

fn parse_startpage(body: &[u8]) -> Vec<String> {
    // OpenSearch suggestions format: ["query", ["sug1", "sug2", ...]]
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(body) else {
        return Vec::new();
    };
    value
        .as_array()
        .and_then(|arr| arr.get(1))
        .and_then(|list| list.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|s| s.as_str())
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

fn parse_qwant(body: &[u8]) -> Vec<String> {
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(body) else {
        return Vec::new();
    };
    value
        .get("data")
        .and_then(|d| d.get("items"))
        .and_then(|items| items.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.get("value").and_then(|v| v.as_str()))
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_urls_match_upstream_endpoints() {
        assert_eq!(
            suggestion_query_url(SuggestionProvider::Google, "42".into(), "en".into()),
            "https://suggestqueries.google.com/complete/search?output=toolbar&q=42"
        );
        assert_eq!(
            suggestion_query_url(SuggestionProvider::Duck, "ab".into(), "en".into()),
            "https://duckduckgo.com/ac/?q=ab"
        );
        assert_eq!(
            suggestion_query_url(SuggestionProvider::Startpage, "gh".into(), "en".into()),
            "https://www.startpage.com/osuggestions?q=gh"
        );
        assert_eq!(
            suggestion_query_url(SuggestionProvider::Qwant, "ij".into(), "en".into()),
            "https://api.qwant.com/v3/suggest?q=ij&locale=en_US&version=2"
        );
    }

    #[test]
    fn google_parses_suggestion_data_attributes() {
        let xml = br#"<?xml version="1.0"?><toplevel>
            <CompleteSuggestion><suggestion data="hello world"/><num_queries int="5"/></CompleteSuggestion>
            <CompleteSuggestion><suggestion data="rust &amp; cargo"/></CompleteSuggestion>
            </toplevel>"#;
        assert_eq!(
            parse_suggestions(SuggestionProvider::Google, xml.to_vec()),
            vec!["hello world", "rust & cargo"]
        );
    }

    #[test]
    fn google_decodes_latin1_body() {
        // "caf\xe9" — é as a raw ISO-8859-1 byte inside the attribute.
        let xml = b"<toplevel><suggestion data=\"caf\xe9\"/></toplevel>".to_vec();
        assert_eq!(
            parse_suggestions(SuggestionProvider::Google, xml),
            vec!["café"]
        );
    }

    #[test]
    fn duck_parses_phrase_objects() {
        let body = br#"[{"phrase":"alpha"},{"phrase":"beta"},{"not_phrase":1}]"#.to_vec();
        assert_eq!(
            parse_suggestions(SuggestionProvider::Duck, body),
            vec!["alpha", "beta"]
        );
    }

    #[test]
    fn startpage_parses_opensearch_second_array() {
        let body = br#"["query",["one","two","three"],{"extra":true}]"#.to_vec();
        assert_eq!(
            parse_suggestions(SuggestionProvider::Startpage, body),
            vec!["one", "two", "three"]
        );
    }

    #[test]
    fn qwant_parses_data_items_value() {
        let body = br#"{"status":"success","data":{"items":[{"value":"alpha"},{"value":"beta"},{"other":1}]}}"#.to_vec();
        assert_eq!(
            parse_suggestions(SuggestionProvider::Qwant, body),
            vec!["alpha", "beta"]
        );
    }

    #[test]
    fn malformed_bodies_yield_empty_lists() {
        for provider in [
            SuggestionProvider::Google,
            SuggestionProvider::Duck,
            SuggestionProvider::Startpage,
            SuggestionProvider::Qwant,
        ] {
            assert!(parse_suggestions(provider, b"{{{not valid".to_vec()).is_empty());
            assert!(parse_suggestions(provider, Vec::new()).is_empty());
        }
    }
}
