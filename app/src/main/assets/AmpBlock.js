// Copyright 2020 CookieJarApps MPL
(function () {
    'use strict';

    if (document.querySelector('.amp_r') !== null) {
        var amp = document.getElementsByClassName("amp_r");
        for (var i = 0; i < amp.length; i++) {
            amp.item(i).removeAttribute('data-amp');
            amp.item(i).removeAttribute('data-amp-cur');
            amp.item(i).removeAttribute('data-amp-title');
            amp.item(i).removeAttribute('data-amp-vgi');
            amp.item(i).removeAttribute('ping');
            amp.item(i).removeAttribute('jsaction');
            amp.item(i).removeAttribute('oncontextmenu');
            amp.item(i).classList.remove("amp_r");
        }
    }

    // Still AMP :( — follow <link rel="canonical"> back to the real page.
    var h = document.getElementsByTagName('html');
    var isAmpDoc = h.length > 0 &&
        (h[0].getAttribute('amp') !== null || h[0].getAttribute('⚡') !== null);
    if (isAmpDoc && window.location.href.indexOf("amp") !== -1) {
        var eles = document.getElementsByTagName('link');
        for (var j = 0; j < eles.length; j++) {
            if (eles[j].getAttribute('rel') === 'canonical') {
                window.location.replace(eles[j].getAttribute('href'));
            }
        }
    }
}());
