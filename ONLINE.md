Some Arktos Mobile features can use online services. Below, the data used by these is detailed.

# Translation

The Reading Mode translator requires a compatible translation endpoint. The feature is off by default so it makes no network requests until enabled; the endpoint defaults to `https://libretranslate.de` and can be changed in the advanced settings. The endpoint must accept POST requests with `text` and `lang` form fields and return a JSON translation.

# Search Suggestions

Arktos Mobile supports search suggestions powered by the selected provider (Google, DuckDuckGo, Baidu or Naver), or none at all. The default is DuckDuckGo; suggestions can be fully disabled in settings. Queries go directly to the chosen provider.

# Third-party content

The ad/malware blocklists bundled with the app come from third-party sources (see `app/src/main/assets/hosts.txt` and `malware.txt`). Homepage shortcuts and search engine queries connect directly to the respective services.
