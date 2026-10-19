Some Arktos Mobile features can use online services. Below, the data used by these is detailed.

# News

Arktos Mobile has a homepage news feed. The news endpoint is disabled by default (empty) and can be set in the advanced settings to any compatible endpoint you operate or trust.

# Translation

The Reading Mode translator requires a compatible translation endpoint. No endpoint is configured by default, so the feature makes no network requests until you set one in the advanced settings. The endpoint must accept POST requests with `text` and `lang` form fields and return a JSON translation.

# Search Suggestions

Arktos Mobile supports search suggestions powered by the selected provider (Google, DuckDuckGo, Baidu or Naver), or none at all. The default is Google; suggestions can be fully disabled in settings. Queries go directly to the chosen provider.

# Third-party content

The ad/malware blocklists bundled with the app come from third-party sources (see `app/src/main/assets/hosts.txt` and `malware.txt`). Homepage shortcuts and search engine queries connect directly to the respective services.
