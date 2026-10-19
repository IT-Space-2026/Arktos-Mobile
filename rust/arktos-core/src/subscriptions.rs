//! AdBlock filter-list preset catalog (LIST01).
//!
//! Verbatim port of the desktop's `src/adblock/adblockpresets.cpp`
//! curated catalog. Each preset is a named remote list the user can
//! subscribe to; subscription state (enabled/last-updated/custom)
//! lives in `ArktosStore`'s `adblock_subscription` table, and the
//! `BlocklistEngine` merges the enabled lists' parsed domains into
//! one lookup set.
//!
//! Default-enabled presets mirror the desktop `AdBlockManager`
//! defaults (uBlock filters + EasyList + EasyPrivacy); everything
//! else ships disabled.

/// One entry in the preset catalog — the desktop
/// `AdBlockListPreset` record plus the default-enabled flag the
/// desktop encodes in `AdBlockManager::load`'s subscription list.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct AdBlockListPreset {
    /// Group shown in the settings UI (desktop `category`).
    pub category: String,
    /// Display name.
    pub name: String,
    /// Fetch URL — also the subscription row id.
    pub url: String,
    /// Desktop's translated blurb.
    pub description: String,
    /// Enabled on first seed, mirroring the desktop defaults.
    pub enabled_by_default: bool,
}

const CORE: &str = "Core & Privacy";
const ANNOYANCES: &str = "Annoyances";
const SECURITY: &str = "Security";
const RESOURCE_ABUSE: &str = "Resource Abuse & Mining";
const MULTIPURPOSE: &str = "Multipurpose";
const REGIONAL: &str = "Regional";

fn preset(
    category: &str,
    name: &str,
    url: &str,
    description: &str,
    enabled_by_default: bool,
) -> AdBlockListPreset {
    AdBlockListPreset {
        category: category.to_owned(),
        name: name.to_owned(),
        url: url.to_owned(),
        description: description.to_owned(),
        enabled_by_default,
    }
}

/// The curated preset catalog — ported verbatim from
/// `adblockpresets.cpp` (same names, URLs, ordering and grouping;
/// only the default-enabled flags are added).
#[uniffi::export]
pub fn adblock_presets() -> Vec<AdBlockListPreset> {
    vec![
        preset(CORE, "uBlock filters",
            "https://ublockorigin.github.io/uAssets/filters/filters.txt",
            "uBlock Origin's primary filter list — enabled by default in uBlock Origin.", true),
        preset(CORE, "EasyList",
            "https://easylist.to/easylist/easylist.txt",
            "The standard ad-blocking list; enabled by default in uBlock Origin.", true),
        preset(CORE, "EasyPrivacy",
            "https://easylist.to/easylist/easyprivacy.txt",
            "Tracker protection; enabled by default in uBlock Origin.", true),
        preset(CORE, "AdGuard Base",
            "https://filters.adtidy.org/extension/ublock/filters/2.txt",
            "AdGuard's own ad-blocking rules, complementary to EasyList.", false),
        preset(CORE, "AdGuard Tracking Protection",
            "https://filters.adtidy.org/extension/ublock/filters/3.txt",
            "Blocks trackers and analytics; complements EasyPrivacy.", false),
        preset(CORE, "AdGuard URL Tracking Protection",
            "https://filters.adtidy.org/extension/ublock/filters/17.txt",
            "Strips tracking parameters from URLs.", false),
        preset(CORE, "Block Outsider Intrusion into LAN",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/lan-block.txt",
            "Blocks public sites from probing your local network (anti-DNS-rebinding).", false),
        preset(CORE, "uBlock filters \u{2013} Privacy",
            "https://ublockorigin.github.io/uAssets/filters/privacy.txt",
            "uBlock Origin's own tracking-protection rules, complementing EasyPrivacy.", false),
        preset(CORE, "uBlock filters \u{2013} Unbreak",
            "https://ublockorigin.github.io/uAssets/filters/unbreak.txt",
            "Repairs site breakage caused by the other filter lists.", false),
        preset(CORE, "uBlock filters \u{2013} Quick fixes",
            "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt",
            "Fast-shipping fixes for urgent filter issues.", false),

        preset(ANNOYANCES, "uBlock filters \u{2013} Annoyances",
            "https://ublockorigin.github.io/uAssets/filters/annoyances.txt",
            "uBlock Origin's combined anti-annoyance rules.", false),
        preset(ANNOYANCES, "AdGuard Annoyances",
            "https://filters.adtidy.org/extension/ublock/filters/14.txt",
            "AdGuard's combined cookie/popup/social-widget annoyance rules.", false),
        preset(ANNOYANCES, "AdGuard Social Media",
            "https://filters.adtidy.org/extension/ublock/filters/4.txt",
            "Removes social-media widgets, like/share buttons, and counters.", false),
        preset(ANNOYANCES, "Anti-Facebook",
            "https://secure.fanboy.co.nz/fanboy-antifacebook.txt",
            "Blocks Facebook trackers and widgets embedded on other sites.", false),
        preset(ANNOYANCES, "Dandelion Sprout's Annoyances List",
            "https://raw.githubusercontent.com/DandelionSprout/adfilt/master/AnnoyancesList",
            "Extra rules for nag screens, surveys, and other annoyances.", false),
        preset(ANNOYANCES, "Actually Legitimate URL Shortener",
            "https://raw.githubusercontent.com/DandelionSprout/adfilt/master/LegitimateURLShortener.txt",
            "Strips tracking junk from shared links (shipped by uBlock Origin).", false),
        preset(ANNOYANCES, "EasyList Annoyances",
            "https://easylist.to/easylist/fanboy-annoyance.txt",
            "Blocks pop-ups, newsletter prompts, and other annoyances (the old Fanboy's Annoyances).", false),
        preset(ANNOYANCES, "EasyList Cookie Notices",
            "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            "Hides cookie-consent banners and GDPR notice pop-ups.", false),
        preset(ANNOYANCES, "EasyList Social",
            "https://easylist.to/easylist/fanboy-social.txt",
            "Removes social-media share buttons, widgets, and embedded feeds.", false),
        preset(ANNOYANCES, "uBlock filters \u{2013} Cookie Notices",
            "https://ublockorigin.github.io/uAssets/filters/annoyances-cookies.txt",
            "uBlock Origin's cookie-banner rules; can be combined with EasyList Cookie Notices.", false),
        preset(ANNOYANCES, "uBlock filters \u{2013} Other Annoyances",
            "https://ublockorigin.github.io/uAssets/filters/annoyances-others.txt",
            "uBlock Origin rules for overlays and other interruptions.", false),
        preset(ANNOYANCES, "uBlock filters \u{2013} Link shorteners",
            "https://ublockorigin.github.io/uAssets/filters/ubo-link-shorteners.txt",
            "Bypasses annoying link-shortener interstitials.", false),

        preset(SECURITY, "uBlock filters \u{2013} Badware risks",
            "https://ublockorigin.github.io/uAssets/filters/badware.txt",
            "Blocks domains known to distribute malware and unwanted software.", false),
        preset(SECURITY, "Online Malicious URL Blocklist",
            "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt",
            "Live abuse.ch URLhaus feed of URLs currently serving malware.", false),
        preset(SECURITY, "Phishing URL Blocklist",
            "https://malware-filter.gitlab.io/malware-filter/phishing-filter.txt",
            "Curated phishing-domain blocklist (the maintained successor to phishing.army).", false),

        preset(RESOURCE_ABUSE, "uBlock filters \u{2013} Resource abuse",
            "https://ublockorigin.github.io/uAssets/filters/resource-abuse.txt",
            "Blocks sites that secretly abuse CPU/bandwidth, e.g. crypto miners (NoCoin successor).", false),
        preset(RESOURCE_ABUSE, "NoCoin",
            "https://raw.githubusercontent.com/hoshsadiq/adblock-nocoin-list/master/nocoin.txt",
            "Dedicated browser crypto-mining blocklist, community maintained.", false),

        preset(MULTIPURPOSE, "Dan Pollock's hosts file",
            "https://someonewhocares.org/hosts/zero/hosts",
            "Long-running hosts-file ad/malware domain blocklist.", false),
        preset(MULTIPURPOSE, "MVPS HOSTS",
            "https://winhelp2002.mvps.org/hosts.txt",
            "Classic hosts-file ad/tracker domain blocklist.", false),
        preset(MULTIPURPOSE, "Peter Lowe's Ad and tracking server list",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext",
            "Compact list of well-known ad/tracking server domains.", false),

        preset(REGIONAL, "EasyList Germany",
            "https://easylist.to/easylistgermany/easylistgermany.txt",
            "German-language site coverage.", false),
        preset(REGIONAL, "Liste FR",
            "https://raw.githubusercontent.com/easylist/listefr/master/liste_fr.txt",
            "French-language site coverage.", false),
        preset(REGIONAL, "RU AdList",
            "https://raw.githubusercontent.com/easylist/ruadlist/master/advblock.txt",
            "Russian-language site coverage.", false),
        preset(REGIONAL, "AdGuard German",
            "https://filters.adtidy.org/extension/ublock/filters/6.txt",
            "AdGuard's own German-language site coverage.", false),
        preset(REGIONAL, "AdGuard French",
            "https://filters.adtidy.org/extension/ublock/filters/16.txt",
            "AdGuard's own French-language site coverage.", false),
        preset(REGIONAL, "EasyList Czech and Slovak",
            "https://raw.githubusercontent.com/tomasko126/easylistczechandslovak/master/filters.txt",
            "Czech and Slovak site coverage.", false),
        preset(REGIONAL, "EasyList Lithuania",
            "https://raw.githubusercontent.com/EasyList-Lithuania/easylist_lithuania/master/easylistlithuania.txt",
            "Lithuanian site coverage.", false),
        preset(REGIONAL, "hufilter",
            "https://hufilter.github.io/hufilter/hufilter.txt",
            "Hungarian site coverage — the community hufilter project, updated daily.", false),
        preset(REGIONAL, "EasyList Spanish",
            "https://easylist-downloads.adblockplus.org/easylistspanish.txt",
            "Spanish-language site coverage.", false),
        preset(REGIONAL, "AdGuard Spanish/Portuguese",
            "https://filters.adtidy.org/extension/ublock/filters/9.txt",
            "Spanish and Portuguese (incl. Brazilian) site coverage.", false),
        preset(REGIONAL, "EasyList Italy",
            "https://easylist-downloads.adblockplus.org/easylistitaly.txt",
            "Italian-language site coverage.", false),
        preset(REGIONAL, "Liste AR",
            "https://easylist-downloads.adblockplus.org/Liste_AR.txt",
            "Arabic-language site coverage.", false),
        preset(REGIONAL, "EasyList China",
            "https://easylist-downloads.adblockplus.org/easylistchina.txt",
            "Chinese-language site coverage.", false),
        preset(REGIONAL, "IndianList",
            "https://easylist-downloads.adblockplus.org/indianlist.txt",
            "Indian-language site coverage.", false),
        preset(REGIONAL, "EasyDutch",
            "https://raw.githubusercontent.com/EasyDutch-uBO/easydutch/main/EasyDutch.txt",
            "Dutch-language site coverage.", false),
        preset(REGIONAL, "EasyList Hebrew",
            "https://raw.githubusercontent.com/easylist/EasyListHebrew/master/EasyListHebrew.txt",
            "Hebrew-language site coverage.", false),
        preset(REGIONAL, "Official Polish Filters",
            "https://raw.githubusercontent.com/MajkiIT/polish-ads-filter/master/polish-adblock-filters/adblock.txt",
            "Polish-language site coverage.", false),
        preset(REGIONAL, "Adblock Bulgarian",
            "https://raw.githubusercontent.com/RealEnder/adblockbg/master/adblockbg.txt",
            "Bulgarian-language site coverage.", false),
        preset(REGIONAL, "ABPVN",
            "https://raw.githubusercontent.com/abpvn/abpvn/master/filter/abpvn.txt",
            "Vietnamese-language site coverage.", false),
        preset(REGIONAL, "AdGuard Chinese",
            "https://filters.adtidy.org/extension/ublock/filters/224.txt",
            "AdGuard's own Chinese-language site coverage.", false),
        preset(REGIONAL, "KO Classic",
            "https://filters.adtidy.org/extension/ublock/filters/227.txt",
            "Korean-language site coverage.", false),
        preset(REGIONAL, "AdGuard Japanese",
            "https://filters.adtidy.org/extension/ublock/filters/7.txt",
            "Japanese-language site coverage.", false),
        preset(REGIONAL, "AdGuard Turkish",
            "https://filters.adtidy.org/extension/ublock/filters/13.txt",
            "Turkish-language site coverage.", false),
        preset(REGIONAL, "Greek Adblock Filter",
            "https://raw.githubusercontent.com/kargig/greek-adblockplus-filter/master/void-gr-filters.txt",
            "Greek-language site coverage.", false),
        preset(REGIONAL, "Estonian List",
            "https://adblock.ee/list.php",
            "Estonian-language site coverage.", false),
        preset(REGIONAL, "Latvian List",
            "https://raw.githubusercontent.com/Latvian-List/adblock-latvian/master/lists/latvian-list.txt",
            "Latvian-language site coverage.", false),
        preset(REGIONAL, "Nordic Filters",
            "https://raw.githubusercontent.com/DandelionSprout/adfilt/master/NorwegianList.txt",
            "Norwegian + Nordic site coverage (DandelionSprout).", false),
        preset(REGIONAL, "Icelandic ABP List",
            "https://adblock.gardar.net/is.abp.txt",
            "Icelandic-language site coverage.", false),
        preset(REGIONAL, "Frellwit's Swedish Filter",
            "https://raw.githubusercontent.com/lassekongo83/Frellwits-filter-lists/master/Frellwits-Swedish-Filter.txt",
            "Swedish-language site coverage (shipped by uBlock Origin).", false),
        preset(REGIONAL, "Finnish List",
            "https://raw.githubusercontent.com/finnish-easylist-addition/finnish-easylist-addition/master/Finland_adb.txt",
            "Finnish-language site coverage.", false),
        preset(REGIONAL, "EasyList Thailand",
            "https://raw.githubusercontent.com/easylist-thailand/easylist-thailand/master/subscription/easylist-thailand.txt",
            "Thai-language site coverage.", false),
        preset(REGIONAL, "ABPindo",
            "https://raw.githubusercontent.com/ABPindo/indonesianadblockrules/master/subscriptions/abpindo.txt",
            "Indonesian-language site coverage.", false),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalog_mirrors_desktop() {
        let presets = adblock_presets();
        // Desktop catalog size: 10 core + 12 annoyances + 3 security +
        // 2 resource-abuse + 3 multipurpose + 32 regional.
        assert_eq!(presets.len(), 62);
        assert_eq!(
            presets.iter().filter(|p| p.category == REGIONAL).count(),
            32
        );
        // Every preset fetches over https — no plaintext remote lists.
        assert!(presets.iter().all(|p| p.url.starts_with("https://")));
        assert!(presets.iter().all(|p| !p.name.is_empty() && !p.category.is_empty()));
        // No duplicate URLs — url doubles as the subscription id.
        let mut urls: Vec<&str> = presets.iter().map(|p| p.url.as_str()).collect();
        urls.sort_unstable();
        urls.dedup();
        assert_eq!(urls.len(), presets.len());
    }

    #[test]
    fn defaults_match_desktop() {
        let defaults: Vec<String> = adblock_presets()
            .iter()
            .filter(|p| p.enabled_by_default)
            .map(|p| p.name.clone())
            .collect();
        // Desktop AdBlockManager defaults: uBlock filters + EasyList
        // + EasyPrivacy.
        assert_eq!(defaults, vec!["uBlock filters", "EasyList", "EasyPrivacy"]);
    }
}
