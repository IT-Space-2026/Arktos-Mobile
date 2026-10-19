//! PORT02 — `Utils.buildErrorPage` / `Utils.buildBlockPage` ported
//! as pure HTML string generation for the interstitial error and
//! block pages. All localized strings and the theme override arrive
//! as parameters so the Kotlin callers no longer need an `Activity`
//! in the generator.
//!
//! UI01 — the pages are styled after the Arktos desktop error pages
//! (`src/htmls/notfound.html`, `src/htmls/certerror.html`): a white
//! rounded card on a light gradient, a blue gradient header band
//! carrying the Arktos logo floated right, the error list under a
//! thin divider, small suggestion text and pill buttons. The dark
//! palette follows the desktop `html.arktos-dark` convention —
//! callers pass a non-empty [color] override fragment exactly when
//! the resolved theme is not LIGHT, so that doubles as the dark-mode
//! signal (same trick as the desktop's `decorateInternalPage`).
//!
//! Quirks preserved from the Kotlin originals: the `</h1><p></p>`
//! sequence after the title, the literal `$!!!` inside the block
//! page's reload script (dead code upstream), and `color` being
//! appended after `</html>` as a raw `<style>` override fragment.

const PAGE_STYLE: &str = "html{-webkit-text-size-adjust: 100%;}body{margin: 0; padding: 3em 1em; background: linear-gradient(to bottom, #ccd 0%, #f6f6fa 45%, #f6f6fa 100%); font-family: \"DejaVu Sans\", sans-serif; color: #222;}#box{background: #fff; border: 1px solid #bbc; border-radius: 0.8em; max-width: 640px; margin: auto; overflow: hidden; box-shadow: 0 2px 14px rgba(34, 34, 85, 0.15);}#header{background: linear-gradient(to bottom, #228 0%, #66a 90%, #668 100%); color: #fff; padding: 20px 28px; overflow: hidden;}#header img{float: right; height: 56px;}#header h1{font-size: 1.3em; font-weight: bold; margin: 0.4em 0;}#content{padding: 22px 28px 26px;}h2{font-size: 1em; font-weight: normal; color: #445; border-bottom: 1px solid #dde; padding-bottom: 0.8em; margin: 0 0 1em;}ul{font-size: 0.85em; color: #556;}ul li{margin: 0.35em 0;}#buttons{margin-top: 1.6em;}#buttons button{display: inline-block; border: 1px solid #66a; border-radius: 0.5em; padding: 0.5em 1.2em; margin: 0 0.8em 0.6em 0; cursor: pointer; -webkit-user-select: none; color: #224; background: linear-gradient(to bottom, #eff, #cdf); font-size: 0.9em;}#buttons button.primary{background: linear-gradient(to bottom, #66a, #558); color: #fff; font-weight: bold;}#buttons button.danger{color: #8a0d0d; border-color: #c99; background: #fff;}html.arktos-dark body{background: linear-gradient(to bottom, #2a2a30 0%, #1e1e23 45%, #1e1e23 100%); color: #e4e4e8;}html.arktos-dark #box{background: #2b2b30; border-color: #474750; box-shadow: 0 2px 14px rgba(0, 0, 0, 0.5);}html.arktos-dark h2{color: #c9c9d2; border-bottom-color: #3f3f48;}html.arktos-dark ul{color: #babac4;}html.arktos-dark #buttons button{border-color: #558; color: #d7dcf0; background: linear-gradient(to bottom, #343a45, #2a2f38);}html.arktos-dark #buttons button.danger{color: #e89a9a; border-color: #6f4444; background: #2b2b30;}@media (max-width: 640px){#buttons button{width: 100%;}}";

/// The bundled logo, embedded as a data URI. These pages are served
/// under the failing page's https origin — or `ERROR_PAGE_ORIGIN`,
/// an `https://` URL — where WebView refuses `file:///android_asset`
/// subresources, so a file-scheme logo URL can never render.
const ARKTOS_LOGO_WEBP: &[u8] = include_bytes!("../../../app/src/main/assets/arktos.webp");

fn arktos_logo() -> &'static str {
    static LOGO: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    LOGO.get_or_init(|| {
        format!(
            "data:image/webp;base64,{}",
            base64_encode(ARKTOS_LOGO_WEBP)
        )
    })
}

fn base64_encode(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((bytes.len() + 2) / 3 * 4);
    for chunk in bytes.chunks(3) {
        let b0 = chunk[0];
        let b1 = *chunk.get(1).unwrap_or(&0);
        let b2 = *chunk.get(2).unwrap_or(&0);
        out.push(ALPHABET[(b0 >> 2) as usize] as char);
        out.push(ALPHABET[((b0 << 4 | b1 >> 4) & 0x3f) as usize] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[((b1 << 2 | b2 >> 6) & 0x3f) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[(b2 & 0x3f) as usize] as char
        } else {
            '='
        });
    }
    out
}

/// The `class="arktos-dark"` attribute for the `<html>` tag — the
/// caller's [color] fragment is only ever non-empty on a non-LIGHT
/// theme, so it doubles as the dark-mode flag.
fn html_class(color: &str) -> &'static str {
    if color.is_empty() {
        ""
    } else {
        " class=\"arktos-dark\""
    }
}

/// `Utils.buildErrorPage` — the network-error interstitial. [color]
/// is the theme override fragment appended verbatim (empty on the
/// light theme), [hint] the small suggestion under the error, and
/// [reload_code] the JS run by the reload button.
#[uniffi::export]
pub fn build_error_page(
    color: String,
    title: String,
    error: String,
    hint: String,
    reload: String,
    show_button: bool,
    reload_code: String,
) -> String {
    let reload_button = if show_button {
        format!(
            "<button onclick=\"reload();\" id=\"reload-button\" class=\"primary\">{reload}</button>"
        )
    } else {
        String::new()
    };

    format!(
        "<html{}><head><script language=\"javascript\"> \
         function reload(){{setTimeout(function(){{{reload_code}}}, 500);}};</script>\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\
         <title>{title}</title><style>{PAGE_STYLE}</style></head><body>\
         <div id=\"box\"><div id=\"header\">\
         <img src=\"{}\" alt=\"Arktos\"><h1>{title}</h1></h1><p></p></div>\
         <div id=\"content\"><h2>{error}</h2><ul><li>{hint}</li></ul>\
         <div id=\"buttons\">{reload_button}</div></div></div>\
         </body></html>{color}",
        html_class(&color),
        arktos_logo()
    )
}

/// `Utils.buildBlockPage` — the blocklist/malware/LAN interstitial.
/// The Activity dependency moved out: [proceed_label]/[go_back_label]
/// are the localized strings Kotlin used to resolve inside.
#[uniffi::export]
pub fn build_block_page(
    color: String,
    title: String,
    error: String,
    blocked_url: String,
    hint: String,
    proceed_label: String,
    go_back_label: String,
    show_proceed: bool,
) -> String {
    let proceed_button = if show_proceed {
        format!(
            "<button onclick=\"location.href = '{blocked_url}';\" id=\"proceed-button\" class=\"danger\">{proceed_label}</button>"
        )
    } else {
        String::new()
    };
    let go_back_button = format!(
        "<button onclick=\"window.history.back();\" id=\"back-button\" class=\"primary\">{go_back_label}</button>"
    );

    format!(
        "<html{}><head><script language=\"javascript\"> \
         function reload(){{setTimeout(function(){{$!!!}}, 500);}};</script>\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\
         <title>{title}</title><style>{PAGE_STYLE}</style></head><body>\
         <div id=\"box\"><div id=\"header\">\
         <img src=\"{}\" alt=\"Arktos\"><h1>{title}</h1></h1><p></p></div>\
         <div id=\"content\"><h2>{error}</h2><ul><li>{blocked_url}</li><li>{hint}</li></ul>\
         <div id=\"buttons\">{go_back_button}{proceed_button}</div></div></div>\
         </body></html>{color}",
        html_class(&color),
        arktos_logo()
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_page_contains_all_inputs() {
        let html = build_error_page(
            "<style>x</style>".into(),
            "No connection".into(),
            "NET::ERR_INTERNET_DISCONNECTED".into(),
            "Check the address or your connection, then try again.".into(),
            "Reload".into(),
            true,
            "window.history.back();".into(),
        );
        assert!(html.contains("<h1>No connection</h1>"));
        assert!(html.contains("<h2>NET::ERR_INTERNET_DISCONNECTED</h2>"));
        assert!(html.contains("<li>Check the address or your connection, then try again.</li>"));
        assert!(html.contains("window.history.back();"));
        assert!(html.contains(">Reload</button>"));
        assert!(html.ends_with("<style>x</style>"));
        assert!(html.contains("data:image/webp;base64,"));
    }

    #[test]
    fn error_page_has_desktop_card_structure() {
        // UI01 — the card design ported from the desktop's
        // notfound.html: rounded #box, gradient #header, #content.
        let html = build_error_page(
            "".into(), "t".into(), "e".into(), "h".into(), "r".into(), true, "x".into(),
        );
        for marker in [
            "<div id=\"box\">",
            "<div id=\"header\">",
            "<div id=\"content\">",
            "<div id=\"buttons\">",
            "border-radius: 0.8em",
            "#228 0%, #66a 90%",
            "float: right",
        ] {
            assert!(html.contains(marker), "missing {marker}");
        }
        assert!(html.starts_with("<html>"));
    }

    #[test]
    fn error_page_dark_class_from_color() {
        // UI01 — a non-empty color fragment is the caller's dark-theme
        // signal, so it tags <html> like decorateInternalPage does.
        let dark = build_error_page(
            "<style>x</style>".into(), "t".into(), "e".into(), "h".into(), "r".into(), true, "x".into(),
        );
        assert!(dark.starts_with("<html class=\"arktos-dark\">"));
        assert!(dark.contains("html.arktos-dark body"));
        let light = build_error_page(
            "".into(), "t".into(), "e".into(), "h".into(), "r".into(), true, "x".into(),
        );
        assert!(light.starts_with("<html>"));
        assert!(!light.contains("arktos-dark\">"));
    }

    #[test]
    fn error_page_hides_button() {
        let html = build_error_page(
            "".into(), "t".into(), "e".into(), "h".into(), "r".into(), false, "x".into(),
        );
        assert!(!html.contains("reload();"));
        // The reload function stub still embeds the code verbatim.
        assert!(html.contains("setTimeout(function(){x}, 500)"));
    }

    #[test]
    fn block_page_contains_buttons_and_url() {
        let html = build_block_page(
            "".into(),
            "Blocked".into(),
            "reason".into(),
            "https://ads.example/x".into(),
            "This page was blocked for your protection.".into(),
            "Proceed".into(),
            "Go back".into(),
            true,
        );
        assert!(html.contains("location.href = 'https://ads.example/x';"));
        assert!(html.contains("<li>https://ads.example/x</li>"));
        assert!(html.contains("<li>This page was blocked for your protection.</li>"));
        assert!(html.contains(">Proceed</button>"));
        assert!(html.contains(">Go back</button>"));
        // The upstream dead `$!!!` in the script tag is preserved.
        assert!(html.contains("$!!!"));
        // The Arktos logo rides in the header band (base64 data URI).
        assert!(html.contains("data:image/webp;base64,"));
    }

    #[test]
    fn block_page_drops_proceed_button() {
        let html = build_block_page(
            "".into(),
            "t".into(),
            "e".into(),
            "https://u/".into(),
            "h".into(),
            "P".into(),
            "G".into(),
            false,
        );
        assert!(!html.contains("location.href"));
        assert!(html.contains(">G</button>"));
    }
}
