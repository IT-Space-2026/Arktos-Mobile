/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Text reflow: while the page is pinch-zoomed, constrain the document
 * to the visible viewport width so text re-wraps instead of needing
 * horizontal scrolling.
 *
 * Detection: WebView's pinch-zoom signal isn't reliable through any
 * single API across versions — onScaleChanged can be suppressed,
 * visualViewport isn't always live, clientWidth behavior varies. So
 * this script polls the *smaller* of the layout and visual viewport
 * widths against the resting width captured at install; a shrink
 * means zoomed in. A <style> element carries the rules so site CSS
 * can't override them; everything is removed when the zoom returns
 * to ~1x. window.__arktosReflowApply is exposed so the app can also
 * drive it with a scale ratio. */
(function () {
    'use strict';

    if (window.__arktosReflowTimer) {
        return;
    }

    var style = document.createElement('style');
    style.id = 'arktos-reflow';
    (document.head || document.documentElement).appendChild(style);

    function visibleWidth() {
        var lw = document.documentElement.clientWidth || window.innerWidth;
        var vw = (window.visualViewport && window.visualViewport.width) || lw;
        return Math.min(lw, vw);
    }

    var baseWidth = visibleWidth();
    var applied = false;
    var lastWidth = 0;

    function apply(width) {
        if (!document.body) {
            return;
        }
        document.body.style.width = width + 'px';
        style.textContent =
            'html,body{max-width:' + width + 'px !important;overflow-x:hidden !important;}' +
            'body *{max-width:100% !important;overflow-wrap:break-word !important;word-wrap:break-word !important;}';
        applied = true;
    }

    function release() {
        if (!applied) {
            return;
        }
        style.textContent = '';
        if (document.body) {
            document.body.style.width = '';
        }
        applied = false;
    }

    /* scale → apply hook kept for the Kotlin onScaleChanged path;
     * scale is the pinch ratio relative to the resting scale. */
    window.__arktosReflowApply = function (scale) {
        if (scale > 1.05) {
            apply(Math.round(baseWidth / scale));
        } else {
            release();
        }
    };

    window.__arktosReflowTimer = setInterval(function () {
        var w = visibleWidth();
        if (w >= baseWidth) {
            lastWidth = 0;
            release();
        } else if (w < baseWidth * 0.98 && w !== lastWidth) {
            lastWidth = w;
            apply(w);
        }
    }, 250);

    if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', function () {
            var w = visibleWidth();
            if (w < baseWidth) {
                lastWidth = w;
                apply(w);
            } else {
                lastWidth = 0;
                release();
            }
        });
    }
}());
