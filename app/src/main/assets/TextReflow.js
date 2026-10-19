/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/* Text reflow on pinch zoom: narrows the body so wrapped text fits the
 * viewport. %%RATIO%% is replaced by the caller — the pinch zoom factor
 * (newScale / initScale), or `document.documentElement.clientWidth` to
 * reset the width. The file is a complete parseable unit; the ratio is
 * substituted into a parenthesized expression, not concatenated into a
 * partial statement. */
(function () {
    'use strict';

    document.body.style.width = (window.innerWidth / (%%RATIO%%)) + 'px';
}());
