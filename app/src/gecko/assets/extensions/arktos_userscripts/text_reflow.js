/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Text reflow on pinch zoom — GeckoView flavor.
 *
 * Same technique as the WebView backend's TextReflow.js (adapted
 * from text-reflow-on-zoom-mobile by emvaized, MIT,
 * https://github.com/emvaized/text-reflow-on-zoom-mobile): never
 * shrink <body>/<html> — that changes the document's content bounds
 * and makes the engine re-clamp its scale mid-gesture. Text-bearing
 * elements are tagged and individually capped to the visible width
 * via a CSS variable.
 *
 * Differences from the WebView variant: there is no app-side
 * evaluateJavascript poll here — Gecko reports visualViewport.scale /
 * .width live, and 'resize' events fire during the pinch itself, so
 * the reflow tracks continuously instead of only on touchend.
 * The on/off state travels through browser.storage.local
 * (reflowEnabled), pushed by the app via the background page's
 * native port — so toggling the setting releases already-open pages
 * without a reload. */
(function () {
    'use strict';

    var TEXT_CLASS = 'arktosReflowText';
    var MAX_WIDTH_VAR = '--arktos-reflow-max-width';
    var XPATH =
        '//p | //h1 | //h2 | //h3 | //h4 | //h5 | //h6 | //li | //pre | ' +
        '//a[normalize-space(text())] | ' +
        '//div[b or em or i or normalize-space(text()) or span[normalize-space(text())]]';

    var enabled = false;
    var observer = null;
    var listenersBound = false;

    function injectStyle() {
        if (document.getElementById('arktos-reflow')) return;
        var style = document.createElement('style');
        style.id = 'arktos-reflow';
        style.textContent =
            '.' + TEXT_CLASS + '{word-wrap:break-word !important;' +
            'overflow-wrap:break-word !important;' +
            'max-width:var(' + MAX_WIDTH_VAR + ',none) !important;}';
        (document.head || document.documentElement).appendChild(style);
    }

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

    function ensureObserver() {
        if (observer || !document.body) return;
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

    function applyForScale(scale, width) {
        if (scale > 1.05) {
            if (!document.documentElement) return;
            document.documentElement.style.setProperty(
                MAX_WIDTH_VAR, Math.round(width * 0.96) + 'px');
            if (document.body && !document.body.querySelector('.' + TEXT_CLASS)) {
                tagTextIn(document.body);
            }
            ensureObserver();
        } else {
            release();
        }
    }

    function release() {
        if (document.documentElement) {
            document.documentElement.style.setProperty(MAX_WIDTH_VAR, 'none');
        }
    }

    var pinching = false;

    function onViewportResize() {
        if (!enabled || !window.visualViewport) return;
        applyForScale(window.visualViewport.scale, window.visualViewport.width);
    }

    function onTouchStart(e) {
        if (e.touches && e.touches.length === 2) pinching = true;
    }

    function onTouchEnd(e) {
        if (!pinching || (e.touches && e.touches.length > 0)) return;
        pinching = false;
        if (!enabled || !window.visualViewport) return;
        applyForScale(window.visualViewport.scale, window.visualViewport.width);
    }

    function bind() {
        if (listenersBound) return;
        listenersBound = true;
        injectStyle();
        if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', onViewportResize);
        }
        window.addEventListener('touchstart', onTouchStart, { passive: true });
        window.addEventListener('touchend', onTouchEnd, { passive: true });
    }

    function setEnabled(value) {
        enabled = !!value;
        if (enabled) {
            bind();
            // Already zoomed when the toggle flipped — apply now.
            if (window.visualViewport && window.visualViewport.scale > 1.05) {
                applyForScale(window.visualViewport.scale, window.visualViewport.width);
            }
        } else {
            release();
        }
    }

    try {
        browser.storage.local.get('reflowEnabled').then(function (result) {
            setEnabled(result && result.reflowEnabled);
        });
        browser.storage.onChanged.addListener(function (changes, area) {
            if (area === 'local' && changes && 'reflowEnabled' in changes) {
                setEnabled(changes.reflowEnabled.newValue);
            }
        });
    } catch (e) {
        // Extension APIs unavailable (e.g. restricted page) — stay off.
    }
}());
