//! Search suggestion core (PORT01).
//!
//! Port of the four `*SuggestionsModel` classes (Google / DuckDuckGo /
//! Baidu / Naver): the Rust core owns the query-URL construction and
//! response parsing; the Kotlin shell keeps the actual HTTP fetch
//! (OkHttp + request factory + RxJava threading).
//!
//! Encoding parity:
//! * Google — toolbar XML, decoded as ISO-8859-1
//!   (`parser.setInput(stream, "ISO-8859-1")` upstream), tokenized for
//!   `<suggestion data="...">` via `html5gum` (entity references in the
//!   attribute are resolved like XmlPullParser did).
//! * DuckDuckGo — UTF-8 JSON array of `{"phrase": "..."}` objects.
//! * Baidu — JSON `[query, [suggestions]]`; the wire encoding is GBK
//!   (upstream's `responseBody.string()` decoded it as UTF-8 and
//!   garbled non-ASCII — the port decodes GBK properly via
//!   `encoding_rs`).
//! * Naver — UTF-8 JSON `{"items":[[[...suggestions...]]]}`.
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
    Baidu,
    Naver,
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
        SuggestionProvider::Baidu => {
            format!("http://suggestion.baidu.com/s?wd={encoded_query}&action=opensearch")
        }
        SuggestionProvider::Naver => format!(
            "https://ac.search.naver.com/nx/ac?q={encoded_query}\
             &q_enc=UTF-8&st=100&frm=nv&r_format=json&r_enc=UTF-8\
             &r_unicode=0&t_koreng=1&ans=2&run=2&rev=4&con=1"
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
        SuggestionProvider::Baidu => parse_baidu(&body),
        SuggestionProvider::Naver => parse_naver(&body),
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

fn parse_baidu(body: &[u8]) -> Vec<String> {
    // Baidu serves GBK; `serde_json` needs UTF-8 so transcode first.
    let (decoded, _, _) = encoding_rs::GBK.decode(body);
    let Ok(value) = serde_json::from_str::<serde_json::Value>(&decoded) else {
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

fn parse_naver(body: &[u8]) -> Vec<String> {
    let Ok(value) = serde_json::from_slice::<serde_json::Value>(body) else {
        return Vec::new();
    };
    value
        .get("items")
        .and_then(|items| items.as_array())
        .and_then(|items| items.first())
        .and_then(|first| first.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|entry| entry.as_array()?.first()?.as_str())
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
            suggestion_query_url(SuggestionProvider::Baidu, "cd".into(), "en".into()),
            "http://suggestion.baidu.com/s?wd=cd&action=opensearch"
        );
        assert_eq!(
            suggestion_query_url(SuggestionProvider::Naver, "ef".into(), "en".into()),
            "https://ac.search.naver.com/nx/ac?q=ef&q_enc=UTF-8&st=100&frm=nv\
             &r_format=json&r_enc=UTF-8&r_unicode=0&t_koreng=1&ans=2&run=2&rev=4&con=1"
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
    fn baidu_parses_second_array_element() {
        let body = br#"["query",["one","two","three"],{"extra":true}]"#.to_vec();
        assert_eq!(
            parse_suggestions(SuggestionProvider::Baidu, body),
            vec!["one", "two", "three"]
        );
    }

    #[test]
    fn baidu_decodes_gbk_body() {
        // GBK-encoded JSON: ["q",["\xD6\xD0\xB9\xFA"]] → ["q",["中国"]]
        let mut body = br#"["q",[""#.to_vec();
        body.extend_from_slice(&[0xD6, 0xD0, 0xB9, 0xFA]);
        body.extend_from_slice(b"\"]]");
        assert_eq!(
            parse_suggestions(SuggestionProvider::Baidu, body),
            vec!["中国"]
        );
    }

    #[test]
    fn naver_parses_items_nested_arrays() {
        let body = br#"{"items":[[["soup","0"],["sugar","0"],["salt","0"]]]}"#.to_vec();
        assert_eq!(
            parse_suggestions(SuggestionProvider::Naver, body),
            vec!["soup", "sugar", "salt"]
        );
    }

    #[test]
    fn malformed_bodies_yield_empty_lists() {
        for provider in [
            SuggestionProvider::Google,
            SuggestionProvider::Duck,
            SuggestionProvider::Baidu,
            SuggestionProvider::Naver,
        ] {
            assert!(parse_suggestions(provider, b"{{{not valid".to_vec()).is_empty());
            assert!(parse_suggestions(provider, Vec::new()).is_empty());
        }
    }
}
