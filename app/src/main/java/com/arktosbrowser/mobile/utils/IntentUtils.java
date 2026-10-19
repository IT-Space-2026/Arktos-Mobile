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
import java.util.List;

import com.arktosbrowser.mobile.R;
import com.arktosbrowser.mobile.constant.Constants;
import com.arktosbrowser.mobile.rust.ArktosCore;

public class IntentUtils {

    @NonNull private final Activity mActivity;

    // PORT02 — the ACCEPTED_URI_SCHEMA regex lives in the Rust core
    // (`ArktosCore.acceptedUriScheme`), shared with UrlUtils.

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

        if (mActivity.getPackageManager().resolveActivity(intent, 0) == null) {
            String packagename = intent.getPackage();
            if (packagename != null) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=pname:"
                    + packagename));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                mActivity.startActivity(intent);
                return true;
            } else {
                return false;
            }
        }
        if (tab != null) {
            intent.putExtra(Constants.INTENT_ORIGIN, tab.hashCode());
        }

        if (ArktosCore.INSTANCE.acceptedUriScheme(url) && !isSpecializedHandlerAvailable(intent)) {
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
