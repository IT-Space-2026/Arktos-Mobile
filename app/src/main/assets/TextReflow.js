/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Text reflow on pinch zoom. Technique adapted from
 * text-reflow-on-zoom-mobile by emvaized (MIT,
 * https://github.com/emvaized/text-reflow-on-zoom-mobile):
 * instead of shrinking <body>/<html> — which changes the document's
 * content bounds and makes WebView re-clamp its scale, killing the
 * zoom ratio mid-gesture — text-bearing elements are tagged and
 * individually capped to the visible width via a CSS variable.
 *
 * Triggers: (a) touchend after a two-finger gesture, computing the
 * visible width from visualViewport where live; (b) the app's
 * WebView.getScale() poll calling __arktosReflowApply(ratio).
 * A MutationObserver tags content added after the initial reflow. */
(function () {
    'use strict';

    if (window.__arktosReflowApply) {
        return;
    }

    var TEXT_CLASS = 'arktosReflowText';
    var MAX_WIDTH_VAR = '--arktos-reflow-max-width';
    var XPATH =
        '//p | //h1 | //h2 | //h3 | //h4 | //h5 | //h6 | //li | //pre | ' +
        '//a[normalize-space(text())] | ' +
        '//div[b or em or i or normalize-space(text()) or span[normalize-space(text())]]';

    var style = document.createElement('style');
    style.id = 'arktos-reflow';
    style.textContent =
        '.' + TEXT_CLASS + '{word-wrap:break-word !important;' +
        'overflow-wrap:break-word !important;' +
        'max-width:var(' + MAX_WIDTH_VAR + ',none) !important;}';
    (document.head || document.documentElement).appendChild(style);

    var baseWidth = document.documentElement.clientWidth || window.innerWidth;
    var observer = null;

    function isTaggable(el) {
        if (!(el instanceof HTMLElement)) return false;
        if (el.classList.contains(TEXT_CLASS)) return false;
        if (!el.textContent || !el.textContent.trim()) return false;
        for (var p = el.parentElement; p; p = p.parentElement) {
            if (p.classList.contains(TEXT_CLASS)) return false;
        }
        return true;
    }

    function tagTextIn(node) {
        var result = document.evaluate(
            XPATH, node, null, XPathResult.UNORDERED_NODE_SNAPSHOT_TYPE, null);
        for (var i = 0; i < result.snapshotLength; i++) {
            var el = result.snapshotItem(i);
            if (isTaggable(el)) el.classList.add(TEXT_CLASS);
        }
    }

    function setWidth(width) {
        document.documentElement.style.setProperty(MAX_WIDTH_VAR, width + 'px');
        if (document.body && !document.body.querySelector('.' + TEXT_CLASS)) {
            tagTextIn(document.body);
        }
        if (!observer && document.body) {
            observer = new MutationObserver(function (muts) {
                for (var m = 0; m < muts.length; m++) {
                    var added = muts[m].addedNodes;
                    for (var n = 0; n < added.length; n++) {
                        if (added[n].nodeType === 1) tagTextIn(added[n]);
                    }
                }
            });
            observer.observe(document.body, { childList: true, subtree: true });
        }
    }

    function release() {
        document.documentElement.style.setProperty(MAX_WIDTH_VAR, 'none');
        if (document.body) document.body.style.width = '';
    }

    /* App-driven: ratio = current pinch scale / resting scale. */
    window.__arktosReflowApply = function (ratio) {
        if (ratio > 1.05) {
            setWidth(Math.round(baseWidth / ratio * 0.96));
        } else {
            release();
        }
    };

    /* Page-driven: a two-finger gesture ending triggers a reflow from
     * visualViewport when it reports live values — covers WebViews
     * where the app-side scale signal misses. */
    var pinching = false;
    window.addEventListener('touchstart', function (e) {
        if (e.touches && e.touches.length === 2) pinching = true;
    }, { passive: true });
    window.addEventListener('touchend', function (e) {
        if (!pinching || (e.touches && e.touches.length > 0)) return;
        pinching = false;
        if (window.visualViewport && window.visualViewport.scale > 1.05) {
            setWidth(Math.round(window.visualViewport.width * 0.96));
        } else if (window.visualViewport && window.visualViewport.scale <= 1.05) {
            release();
        }
    }, { passive: true });
}());
