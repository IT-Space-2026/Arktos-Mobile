/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Desktop-mode helper: forces a 1024px layout viewport. Pages without a
 * viewport meta tag get one created — the old script dereferenced null
 * there. */
(function () {
    'use strict';

    var width = 1024;
    var metaViewport = document.querySelector('meta[name="viewport"]');
    if (!metaViewport) {
        metaViewport = document.createElement('meta');
        metaViewport.setAttribute('name', 'viewport');
        var head = document.head || document.getElementsByTagName('head')[0];
        if (!head) {
            return;
        }
        head.appendChild(metaViewport);
    }
    metaViewport.setAttribute(
        'content',
        'width=' + width + ', initial-scale=' + (window.screen.width / width)
    );
}());
