//! PORT02 — `Utils.buildErrorPage` / `Utils.buildBlockPage` ported
//! verbatim: pure HTML string generation for the interstitial error
//! and block pages. All localized strings and the theme override
//! arrive as parameters so the Kotlin callers no longer need an
//! `Activity` in the generator.
//!
//! Quirks preserved from the Kotlin originals: the `</h1><p></p>`
//! sequence after the title, the literal `$!!!` inside the block
//! page's reload script (dead code upstream), and `color` being
//! appended after `</html>` as a raw `<style>` override fragment.

const PAGE_STYLE: &str = "html{-webkit-text-size-adjust: 100%;font-size: 125%;}body{background-color:#ffffff; color: #646464; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif, \"Apple Color Emoji\", \"Segoe UI Emoji\", \"Segoe UI Symbol\"; font-size: 75%;}div{display:block;}h1{margin-top: 0; color: #333; font-size: 1.6em; font-weight: normal; line-height: 1.25em; margin-bottom: 16px;}button{border: solid 1px; border-radius: 4px; border-color: #000000FF; padding: 0 16px; min-width: 64px; line-height: 34px; background-color: transparent; -webkit-user-select: none; text-transform: uppercase; color: rgb(76, 142, 250); box-sizing: border-box; cursor: pointer; font-size: .875em; margin: 0; font-weight: 500;}button:hover{box-shadow: 0 1px 2px rgba(1, 1, 1, 0.5);}.error-code{color: #777; display: inline; font-size: .86667em; margin-top: 15px; opacity: .5; text-transform: uppercase;}.interstitial-wrapper{box-sizing: border-box;font-size: 1em;margin: 100px auto 0;max-width: 600px;width: 100%;}.offline .interstitial-wrapper{color: #2b2b2b;font-size: 1em;line-height: 1.55;margin: 0 auto;max-width: 600px;padding-top: 100px;width: 100%;}.hidden{display: none;}.nav-wrapper{margin-top: 51px; display:inline-block;}#buttons::after{clear: both; content: ''; display: block; width: 100%;}.nav-wrapper::after{clear: both; content: ''; display: table; width: 100%;}.small-link{color: #696969; font-size: .875em;}@media (max-width: 640px), (max-height: 640px){h1{margin: 0 0 15px;}button{width: 100%;}}";

/// `Utils.buildErrorPage` — the network-error interstitial. [color]
/// is the theme override fragment appended verbatim (empty on the
/// light theme), [reload_code] the JS run by the reload button.
#[uniffi::export]
pub fn build_error_page(
    color: String,
    title: String,
    error: String,
    reload: String,
    show_button: bool,
    reload_code: String,
) -> String {
    let reload_button = if show_button {
        format!(
            "<button onclick=\"reload();\" id=\"reload-button\" class=\"blue-button text-button reload\">{reload}</button>"
        )
    } else {
        String::new()
    };

    format!(
        "<html><head><script language=\"javascript\"> \
         function reload(){{setTimeout(function(){{{reload_code}}}, 500);}};</script>\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\
         <style>{PAGE_STYLE}</style></head><center><body class=\"offline\">\
         <div class=\"interstitial-wrapper\"><div id=\"main-content\">\
         <img src=\"file:///android_asset/warning.webp\" height=\"52\" width=\"52\"><br><br>\
         <div class=\"icon icon-offline\"></div><div id=\"main-message\">\
         <h1>{title}</h1></h1><p></p><div class=\"error-code\">{error}</div></div></div>\
         <div id=\"buttons\" class=\"nav-wrapper\"><div id=\"control-buttons\">{reload_button}\
         </div></div></div></body></center></html>{color}"
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
    proceed_label: String,
    go_back_label: String,
    show_proceed: bool,
) -> String {
    let proceed_button = if show_proceed {
        format!(
            "<button onclick=\"location.href = '{blocked_url}';\" id=\"reload-button\" class=\"blue-button text-button reload\">{proceed_label}</button>"
        )
    } else {
        String::new()
    };
    let go_back_button = format!(
        "<button onclick=\"window.history.back();\" id=\"reload-button\" class=\"blue-button text-button reload\">{go_back_label}</button>"
    );

    format!(
        "<html><head><script language=\"javascript\"> \
         function reload(){{setTimeout(function(){{$!!!}}, 500);}};</script>\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\
         <style>{PAGE_STYLE}</style></head><center><body class=\"offline\">\
         <div class=\"interstitial-wrapper\"><div id=\"main-content\">\
         <div class=\"icon icon-offline\"></div><div id=\"main-message\">\
         <h1>{title}</h1></h1><p></p><div class=\"error-code\">{error}</div></div></div>\
         <div id=\"buttons\" class=\"nav-wrapper\"><div id=\"control-buttons\">\
         {proceed_button} <br /><br /> {go_back_button}\
         </div></div></div></body></center></html>{color}"
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
            "Reload".into(),
            true,
            "window.history.back();".into(),
        );
        assert!(html.contains("<h1>No connection</h1>"));
        assert!(html.contains("NET::ERR_INTERNET_DISCONNECTED"));
        assert!(html.contains("window.history.back();"));
        assert!(html.contains(">Reload</button>"));
        assert!(html.ends_with("<style>x</style>"));
        assert!(html.contains("warning.webp"));
    }

    #[test]
    fn error_page_hides_button() {
        let html = build_error_page("".into(), "t".into(), "e".into(), "r".into(), false, "x".into());
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
            "Proceed".into(),
            "Go back".into(),
            true,
        );
        assert!(html.contains("location.href = 'https://ads.example/x';"));
        assert!(html.contains(">Proceed</button>"));
        assert!(html.contains(">Go back</button>"));
        // The upstream dead `$!!!` in the script tag is preserved.
        assert!(html.contains("$!!!"));
        // No warning image on the block page.
        assert!(!html.contains("warning.webp"));
    }

    #[test]
    fn block_page_drops_proceed_button() {
        let html = build_block_page(
            "".into(),
            "t".into(),
            "e".into(),
            "https://u/".into(),
            "P".into(),
            "G".into(),
            false,
        );
        assert!(!html.contains("location.href"));
        assert!(html.contains(">G</button>"));
    }
}
