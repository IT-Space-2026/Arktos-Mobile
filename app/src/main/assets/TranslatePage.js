/* Arktos Mobile in-page translation.
 *
 * Walks visible text nodes and sends them in batches to the
 * ArktosTranslate JavascriptInterface, which performs the network
 * call in Kotlin and returns translated strings in the same order.
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

    for (var i = 0; i < nodes.length; i += BATCH_SIZE) {
        var slice = nodes.slice(i, i + BATCH_SIZE);
        var texts = [];
        for (var j = 0; j < slice.length; j++) {
            texts.push(slice[j].nodeValue);
        }
        try {
            var reply = window.ArktosTranslate.translate(
                JSON.stringify({ texts: texts, target: TARGET })
            );
            var translated = JSON.parse(reply);
            if (translated && translated.length === slice.length) {
                for (var k = 0; k < slice.length; k++) {
                    slice[k].nodeValue = translated[k];
                }
            }
        } catch (e) {
            // Endpoint down, bridge detached, or a non-JSON reply —
            // keep whatever was already translated and stop.
            break;
        }
    }

    document.documentElement.setAttribute('data-arktos-translated', TARGET);
}());
