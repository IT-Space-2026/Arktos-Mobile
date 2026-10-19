//! Page and selected-text translation — LibreTranslate-compatible wire
//! format. Network I/O stays in Kotlin (`TranslateClient`, same split as
//! `RankListLoader`); Rust owns the request body, endpoint normalization
//! and response parsing so the protocol lives in one tested place.

use serde_json::json;

#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum TranslateError {
    Serialize,
    InvalidJson,
    MissingField,
    UnexpectedShape,
}

impl std::fmt::Display for TranslateError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let msg = match self {
            TranslateError::Serialize => "failed to serialize translation request",
            TranslateError::InvalidJson => "translation response is not valid JSON",
            TranslateError::MissingField => "translation response has no translatedText",
            TranslateError::UnexpectedShape => "translatedText has unexpected shape",
        };
        f.write_str(msg)
    }
}

/// Validated `{"texts":[…],"target":"xx"}` walker request.
#[derive(uniffi::Record)]
pub struct TranslateRequest {
    pub texts: Vec<String>,
    pub target: String,
}

/// Parses and validates a TranslatePage.js request. Returns `None` for
/// malformed JSON, missing/non-string array members, an empty `texts`,
/// or a `target` that is not a sane BCP-47 tag — page-controlled input
/// must never reach the request builder unvalidated.
#[uniffi::export]
pub fn translate_parse_request(body: String) -> Option<TranslateRequest> {
    let value: serde_json::Value = serde_json::from_str(&body).ok()?;
    let texts: Vec<String> = value
        .get("texts")?
        .as_array()?
        .iter()
        .map(|item| item.as_str().map(str::to_owned))
        .collect::<Option<_>>()?;
    if texts.is_empty() {
        return None;
    }
    let target = value
        .get("target")
        .and_then(|t| t.as_str())
        .unwrap_or("en");
    if !is_valid_lang_tag(target) {
        return None;
    }
    Some(TranslateRequest {
        texts,
        target: target.to_owned(),
    })
}

/// BCP-47-ish shape: 2-8 alpha primary subtag plus alphanumeric
/// subtags of up to 8 chars (`en`, `zh-Hant`, `zh-Hant-TW`).
fn is_valid_lang_tag(tag: &str) -> bool {
    let mut parts = tag.split('-');
    let primary = parts.next().unwrap_or("");
    if !(2..=8).contains(&primary.len())
        || !primary.chars().all(|c| c.is_ascii_alphabetic())
    {
        return false;
    }
    parts.all(|p| !p.is_empty() && p.len() <= 8 && p.chars().all(|c| c.is_ascii_alphanumeric()))
}

/// Joins a user-configured endpoint base with the LibreTranslate route.
/// Accepts `https://host`, `https://host/` or a full `…/translate` URL.
#[uniffi::export]
pub fn translate_url(endpoint: String) -> String {
    let base = endpoint.trim().trim_end_matches('/');
    if base.ends_with("/translate") {
        base.to_owned()
    } else {
        format!("{base}/translate")
    }
}

/// Builds `{"q":[…],"source":"…","target":"…","format":"text"}`.
/// `q` is always an array so batch and single-text requests share one
/// shape; `source` may be `"auto"`.
#[uniffi::export]
pub fn translate_request_body(
    texts: Vec<String>,
    source: String,
    target: String,
) -> Result<String, TranslateError> {
    serde_json::to_string(&json!({
        "q": texts,
        "source": source,
        "target": target,
        "format": "text",
    }))
    .map_err(|_| TranslateError::Serialize)
}

/// Parses a LibreTranslate `{"translatedText": …}` response, which is a
/// string for single `q` and an array for batched `q`. The legacy
/// `{"text": …}` shape used by the old selected-text provider is also
/// accepted so both call sites share the parser.
#[uniffi::export]
pub fn parse_translate_response(body: String) -> Result<Vec<String>, TranslateError> {
    let value: serde_json::Value =
        serde_json::from_str(&body).map_err(|_| TranslateError::InvalidJson)?;
    let translated = value
        .get("translatedText")
        .or_else(|| value.get("text"))
        .ok_or(TranslateError::MissingField)?;
    match translated {
        serde_json::Value::String(s) => Ok(vec![s.clone()]),
        serde_json::Value::Array(items) => items
            .iter()
            .map(|item| {
                item.as_str()
                    .map(str::to_owned)
                    .ok_or(TranslateError::UnexpectedShape)
            })
            .collect(),
        _ => Err(TranslateError::UnexpectedShape),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_joins_translate_route() {
        assert_eq!(translate_url("https://t.example".into()), "https://t.example/translate");
        assert_eq!(translate_url("https://t.example/".into()), "https://t.example/translate");
        assert_eq!(
            translate_url("https://t.example/translate".into()),
            "https://t.example/translate"
        );
    }

    #[test]
    fn request_body_is_libretranslate_shape() {
        let body =
            translate_request_body(vec!["Hello".into(), "World".into()], "auto".into(), "hu".into())
                .unwrap();
        let v: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(v["q"], serde_json::json!(["Hello", "World"]));
        assert_eq!(v["source"], "auto");
        assert_eq!(v["target"], "hu");
        assert_eq!(v["format"], "text");
    }

    #[test]
    fn parses_single_and_batched_responses() {
        let single = parse_translate_response(r#"{"translatedText":"Szia"}"#.into()).unwrap();
        assert_eq!(single, vec!["Szia"]);
        let batch =
            parse_translate_response(r#"{"translatedText":["Szia","Világ"]}"#.into()).unwrap();
        assert_eq!(batch, vec!["Szia", "Világ"]);
    }

    #[test]
    fn parses_legacy_text_field() {
        let out = parse_translate_response(r#"{"text":"Hallo"}"#.into()).unwrap();
        assert_eq!(out, vec!["Hallo"]);
    }

    #[test]
    fn parses_valid_walker_requests() {
        let req = translate_parse_request(
            r#"{"texts":["Hello","World"],"target":"zh-Hant-TW"}"#.into(),
        )
        .unwrap();
        assert_eq!(req.texts, vec!["Hello", "World"]);
        assert_eq!(req.target, "zh-Hant-TW");

        let defaulted = translate_parse_request(r#"{"texts":["x"]}"#.into()).unwrap();
        assert_eq!(defaulted.target, "en");
    }

    #[test]
    fn rejects_bad_walker_requests() {
        // Malformed JSON, missing array, non-string members, empty
        // texts, and page-injected garbage targets are all refused.
        for body in [
            "not json",
            r#"{"texts":{}}"#,
            r#"{"other":["x"]}"#,
            r#"{"texts":[1],"target":"hu"}"#,
            r#"{"texts":[],"target":"hu"}"#,
            r#"{"texts":["x"],"target":"hu'; DROP TABLE--"}"#,
            r#"{"texts":["x"],"target":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"#,
            r#"{"texts":["x"],"target":"hu-"}"#,
        ] {
            assert!(translate_parse_request(body.into()).is_none(), "accepted {body}");
        }
    }

    #[test]
    fn rejects_bad_json_and_missing_field() {
        assert!(matches!(
            parse_translate_response("not json".into()),
            Err(TranslateError::InvalidJson)
        ));
        assert!(matches!(
            parse_translate_response("{}".into()),
            Err(TranslateError::MissingField)
        ));
        assert!(matches!(
            parse_translate_response(r#"{"translatedText":[1]}"#.into()),
            Err(TranslateError::UnexpectedShape)
        ));
    }
}
