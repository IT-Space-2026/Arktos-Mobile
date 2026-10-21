/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Text reflow hook. Injected once per page; driven exclusively by the
 * app's getScale() poll calling window.__arktosReflowApply(ratio),
 * where ratio is the pinch zoom relative to the page's resting scale.
 * While zoomed, the document is constrained to the visible width so
 * text re-wraps; the rules live in a <style> element so site CSS
 * can't override them, and are removed when the zoom returns to ~1x.
 *
 * No self-detection inside the page: clientWidth does not shrink on
 * WebView page-scale pinch, so an in-page poll would misread "not
 * zoomed" and strip the rules the app just applied. */
(function () {
    'use strict';

    if (window.__arktosReflowApply) {
        return;
    }

    var style = document.createElement('style');
    style.id = 'arktos-reflow';
    (document.head || document.documentElement).appendChild(style);

    /* Layout viewport width — constant under page-scale pinch zoom. */
    var baseWidth = document.documentElement.clientWidth || window.innerWidth;

    window.__arktosReflowApply = function (scale) {
        if (!document.body) {
            return;
        }
        if (scale <= 1.05) {
            style.textContent = '';
            document.body.style.width = '';
            return;
        }
        var width = Math.round(baseWidth / scale);
        document.body.style.width = width + 'px';
        style.textContent =
            'html,body{max-width:' + width + 'px !important;overflow-x:hidden !important;}' +
            'body *{max-width:100% !important;overflow-wrap:break-word !important;word-wrap:break-word !important;}';
    };
}());
