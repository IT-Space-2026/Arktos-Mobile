/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Text reflow: while the page is pinch-zoomed, constrain the document
 * to the visible viewport width so text re-wraps instead of needing
 * horizontal scrolling. Driven by the Visual Viewport API — WebView
 * pinch zoom reports through visualViewport even when
 * WebViewClient.onScaleChanged is never delivered. A <style> element
 * carries the rules so site CSS can't easily override them, and the
 * constraints are removed once the zoom returns to ~1x. Injected once
 * per page load by ArktosCookieWebClient. */
(function () {
    'use strict';

    if (window.__arktosReflow) {
        return;
    }
    window.__arktosReflow = true;

    var style = document.createElement('style');
    style.id = 'arktos-reflow';
    (document.head || document.documentElement).appendChild(style);

    function reflow() {
        var vp = window.visualViewport;
        var scale = vp ? vp.scale : 1;
        if (scale <= 1.05) {
            style.textContent = '';
            if (document.body) {
                document.body.style.width = '';
            }
            return;
        }
        var width = Math.round(vp ? vp.width : window.innerWidth / scale);
        if (document.body) {
            document.body.style.width = width + 'px';
        }
        style.textContent =
            'html,body{max-width:' + width + 'px !important;overflow-x:hidden !important;}' +
            'body *{max-width:100% !important;overflow-wrap:break-word !important;word-wrap:break-word !important;}';
    }

    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', reflow);
    }
    reflow();
}());
