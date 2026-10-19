//! Readability port (PORT01) — replaces `readability4j` in
//! `ReadingActivity` with `dom_smoothie`, the same readability.js
//! port the desktop core uses.
//!
//! Kotlin keeps data sourcing (it fetches the page HTML over the
//! network); the extraction itself — article title, cleaned content,
//! text, byline, excerpt — happens here.

/// Result of readability extraction, mirroring the fields
/// `ReadingActivity` consumed off readability4j's `Article`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReadableArticle {
    /// Article title.
    pub title: String,
    /// Extracted HTML content (relative URLs already resolved).
    pub content: String,
    /// Plain-text content.
    pub text_content: String,
    /// Author byline, when detected.
    pub byline: Option<String>,
    /// Short excerpt, when detected.
    pub excerpt: Option<String>,
}

/// Run readability extraction on a fetched page. `url` is used only
/// to resolve relative URLs, exactly like upstream's
/// `Readability4J(url, html)`. `None` when no readable article is
/// found (upstream threw / returned empty fields).
#[uniffi::export]
pub fn extract_readable_article(url: String, html: String) -> Option<ReadableArticle> {
    let mut readability =
        dom_smoothie::Readability::new(html.as_str(), Some(url.as_str()), Default::default())
            .ok()?;
    readability.parse().ok().map(|article| ReadableArticle {
        title: article.title,
        content: article.content.to_string(),
        text_content: article.text_content.to_string(),
        byline: article.byline,
        excerpt: article.excerpt,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const ARTICLE_HTML: &str = r#"<!DOCTYPE html>
<html><head><title>Test Article</title></head>
<body>
<article>
<h1>Test Article</h1>
<p>This is the first paragraph of a fairly substantial article body.
Readability needs enough textual content to score a candidate node, so
this paragraph carries a few sentences of plain English prose.</p>
<p>A second paragraph with more text so the extractor has something to
hold on to. <a href="/relative/link">A relative link</a> and an
<img src="/img/pic.png"> image.</p>
</article>
<div class="ad">BUY THINGS</div>
</body></html>"#;

    #[test]
    fn extracts_article_fields() {
        let article = extract_readable_article(
            "https://example.com/post".to_owned(),
            ARTICLE_HTML.to_owned(),
        )
        .expect("readable article");
        assert_eq!(article.title, "Test Article");
        assert!(article.content.contains("first paragraph"));
        assert!(article.text_content.contains("second paragraph"));
        // relative URLs resolved against the page url
        assert!(article.content.contains("https://example.com/relative/link"));
    }

    #[test]
    fn garbage_input_returns_none_or_empty() {
        // Should not panic; may return None or a thin article.
        let _ = extract_readable_article(
            "https://example.com".to_owned(),
            "not html at all".to_owned(),
        );
    }
}
