package com.arktosbrowser.mobile.utils;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;
import android.webkit.WebView;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arktosbrowser.mobile.R;
import com.arktosbrowser.mobile.constant.Constants;

public class IntentUtils {

    @NonNull private final Activity mActivity;

    private static final Pattern ACCEPTED_URI_SCHEMA = Pattern.compile("(?i)"
        + // switch on case insensitive matching
        '('
        + // begin group for schema
        "(?:http|https|file)://" + "|(?:inline|data|about|javascript):" + "|(?:.*:.*@)"
        + ')' + "(.*)");

    // Extra placed by sites in intent:// URIs naming a page to load when
    // the pinned app is not installed (S.browser_fallback_url=...).
    private static final String EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url";

    public IntentUtils(@NonNull Activity activity) {
        mActivity = activity;
    }

    public boolean startActivityForUrl(@Nullable WebView tab, @NonNull String url) {
        Intent intent;
        try {
            intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException ex) {
            Log.w("Browser", "Bad URI " + url + ": " + ex.getMessage());
            return false;
        }

        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setComponent(null);
        intent.setSelector(null);

        PackageManager packageManager = mActivity.getPackageManager();
        boolean pinnedResolvable = packageManager.resolveActivity(intent, 0) != null;
        Intent unpinned = null;
        boolean otherHandlerInstalled = false;
        if (!pinnedResolvable) {
            // The app the link pinned via package= is not installed. Before
            // sending the user to the store, check whether any other
            // installed app specifically handles this link — e.g. a
            // third-party client registered for the site's URLs (upstream
            // issue #316).
            unpinned = new Intent(intent);
            unpinned.setPackage(null);
            otherHandlerInstalled = hasInstalledSpecializedHandler(unpinned);
        }
        // The fallback URL can only be honoured when we have a tab to load
        // it into.
        String fallbackUrl = tab != null
            ? intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL) : null;

        switch (ExternalAppLaunchPolicy.decide(pinnedResolvable,
            otherHandlerInstalled, fallbackUrl, intent.getPackage())) {
            case LAUNCH:
                if (!pinnedResolvable) {
                    // Let the other installed handler claim the link.
                    intent = unpinned;
                }
                break;
            case LOAD_FALLBACK:
                tab.loadUrl(fallbackUrl);
                return true;
            case OPEN_MARKET:
                Intent market = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=pname:" + intent.getPackage()));
                market.addCategory(Intent.CATEGORY_BROWSABLE);
                try {
                    mActivity.startActivity(market);
                } catch (Exception ignored) {
                    // No store app on this device either — stay in browser.
                    return false;
                }
                return true;
            case STAY_IN_BROWSER:
            default:
                return false;
        }
        if (tab != null) {
            intent.putExtra(Constants.INTENT_ORIGIN, tab.hashCode());
        }

        Matcher m = ACCEPTED_URI_SCHEMA.matcher(url);
        if (m.matches() && !isSpecializedHandlerAvailable(intent)) {
            return false;
        }
        try {
            if (mActivity.startActivityIfNeeded(intent, -1)) {
                return true;
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            // TODO: 6/5/17 fix case where this could throw a FileUriExposedException due to file:// urls
        }
        return false;
    }

    /**
     * Whether an installed app — other than the package the link pinned —
     * specifically handles this intent's target. For web URLs that means a
     * filter whose data authorities cover the URL's host: a generic browser
     * declaring bare http/https schemes doesn't count, which also stops the
     * link looping back into this browser. For non-web targets any matched
     * handler counts.
     */
    private boolean hasInstalledSpecializedHandler(@NonNull Intent intent) {
        List<ResolveInfo> handlers = mActivity.getPackageManager()
            .queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER);
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }
        Uri data = intent.getData();
        if (data == null) {
            // Action-only intent — browsers can't match those, so anything
            // that resolved is a real handler.
            return true;
        }
        String scheme = data.getScheme();
        boolean webScheme = "http".equalsIgnoreCase(scheme)
            || "https".equalsIgnoreCase(scheme);
        for (ResolveInfo resolveInfo : handlers) {
            IntentFilter filter = resolveInfo.filter;
            if (filter == null) {
                continue;
            }
            if (webScheme) {
                List<String> authorities =
                    new ArrayList<>(filter.countDataAuthorities());
                for (int i = 0; i < filter.countDataAuthorities(); i++) {
                    IntentFilter.AuthorityEntry entry = filter.getDataAuthority(i);
                    if (entry != null && entry.getHost() != null) {
                        authorities.add(entry.getHost());
                    }
                }
                if (ExternalAppLaunchPolicy.authorityMatchesHost(authorities,
                    data.getHost())) {
                    return true;
                }
            } else if (filter.countDataAuthorities() > 0
                || (filter.countDataSchemes() > 0
                    && !filter.hasDataScheme("http")
                    && !filter.hasDataScheme("https"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Search for intent handlers that are specific to this URL aka, specialized
     * apps like google maps or youtube
     */
    private boolean isSpecializedHandlerAvailable(@NonNull Intent intent) {
        PackageManager pm = mActivity.getPackageManager();
        List<ResolveInfo> handlers = pm.queryIntentActivities(intent,
            PackageManager.GET_RESOLVED_FILTER);
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }
        for (ResolveInfo resolveInfo : handlers) {
            IntentFilter filter = resolveInfo.filter;
            if (filter == null) {
                // No intent filter matches this intent?
                // Error on the side of staying in the browser, ignore
                continue;
            }
            // NOTICE: Use of && instead of || will cause the browser
            // to launch a new intent for every URL, using OR only
            // launches a new one if there is a non-browser app that
            // can handle it.
            // Previously we checked the number of data paths, but it is unnecessary
            // filter.countDataAuthorities() == 0 || filter.countDataPaths() == 0
            if (filter.countDataAuthorities() == 0) {
                // Generic handler, skip
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Shares a URL to the system.
     *
     * @param url   the URL to share. If the URL is null
     *              or a special URL, no sharing will occur.
     * @param title the title of the URL to share. This
     *              is optional.
     */
    public void shareUrl(@Nullable String url, @Nullable String title) {
        if (url != null && !UrlUtils.isSpecialUrl(url)) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            if (title != null) {
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            }
            shareIntent.putExtra(Intent.EXTRA_TEXT, url);
            mActivity.startActivity(Intent.createChooser(shareIntent, mActivity.getString(R.string.dialog_title_share)));
        }
    }
}
