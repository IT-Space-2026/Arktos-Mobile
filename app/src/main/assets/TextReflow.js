/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Text reflow: while the page is pinch-zoomed, constrain the document
 * to the visible viewport width so text re-wraps instead of needing
 * horizontal scrolling. Injected once per page load by
 * ArktosCookieWebClient.onPageFinished; exposes
 * window.__arktosReflowApply(scale) so both signals can drive it —
 * WebViewClient.onScaleChanged (the reliable WebView pinch signal)
 * and visualViewport.resize (where supported). The constraints are
 * removed when the zoom returns to ~1x. */
(function () {
    'use strict';

    if (window.__arktosReflowApply) {
        return;
    }

    var style = document.createElement('style');
    style.id = 'arktos-reflow';
    (document.head || document.documentElement).appendChild(style);

    window.__arktosReflowApply = function (scale) {
        if (!document.body) {
            return;
        }
        if (scale <= 1.05) {
            style.textContent = '';
            document.body.style.width = '';
            return;
        }
        var width = Math.round(window.innerWidth / scale);
        document.body.style.width = width + 'px';
        style.textContent =
            'html,body{max-width:' + width + 'px !important;overflow-x:hidden !important;}' +
            'body *{max-width:100% !important;overflow-wrap:break-word !important;word-wrap:break-word !important;}';
    };

    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', function () {
            window.__arktosReflowApply(window.visualViewport.scale);
        });
    }
}());
