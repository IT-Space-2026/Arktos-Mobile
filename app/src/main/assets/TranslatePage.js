/* Arktos Mobile in-page translation.
 *
 * Walks visible text nodes and sends them in batches to the
 * ArktosTranslate web-message listener, which performs the network
 * call in Kotlin and posts back translated strings in the same order.
 * %%TARGET_JSON%% is replaced with a JSON-quoted BCP-47 language tag
 * before injection (see translate/TranslateBridge.kt).
 */
(function () {
    'use strict';

    var TARGET = %%TARGET_JSON%%;
    var BATCH_SIZE = 25;
    var SKIP_TAGS = {
        SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1,
        CODE: 1, PRE: 1, KBD: 1, SAMP: 1
    };

    if (!window.ArktosTranslate ||
        document.documentElement.hasAttribute('data-arktos-translated') ||
        !document.body) {
        return;
    }

    var walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_TEXT,
        null
    );
    var nodes = [];
    var node;
    while ((node = walker.nextNode())) {
        var parent = node.parentElement;
        if (!parent || SKIP_TAGS[parent.tagName]) {
            continue;
        }
        var text = node.nodeValue;
        if (text && text.trim().length > 1) {
            nodes.push(node);
        }
    }

    var seq = 0;
    var offset = 0;
    var pending = {};

    function finish() {
        window.ArktosTranslate.onmessage = null;
        document.documentElement.setAttribute('data-arktos-translated', TARGET);
    }

    function sendNext() {
        if (offset >= nodes.length) {
            finish();
            return;
        }
        var slice = nodes.slice(offset, offset + BATCH_SIZE);
        offset += BATCH_SIZE;
        var texts = [];
        for (var j = 0; j < slice.length; j++) {
            texts.push(slice[j].nodeValue);
        }
        var id = ++seq;
        pending[id] = slice;
        try {
            window.ArktosTranslate.postMessage(
                JSON.stringify({ id: id, texts: texts, target: TARGET })
            );
        } catch (e) {
            // Bridge detached mid-walk — keep whatever was translated.
            finish();
        }
    }

    window.ArktosTranslate.onmessage = function (ev) {
        var m;
        try { m = JSON.parse(ev.data); } catch (e) { return; }
        var slice = pending[m.id];
        if (!slice) return;
        delete pending[m.id];
        var translated = m.translated;
        if (translated && translated.length === slice.length) {
            for (var k = 0; k < slice.length; k++) {
                slice[k].nodeValue = translated[k];
            }
        }
        sendNext();
    };

    sendNext();
}());
