/* Dark "web addon" fallback for WebViews without native FORCE_DARK
 * (pre-Android Q / old WebView). Inverts the page and re-inverts media
 * so images and video keep their colors. Idempotent: the style node is
 * keyed by id, so repeat injections do nothing instead of stacking.
 */
(function () {
    'use strict';

    var STYLE_ID = 'arktos-dark-mode';
    if (document.getElementById(STYLE_ID)) {
        return;
    }

    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.type = 'text/css';
    style.appendChild(document.createTextNode(
        'html { filter: invert(100%) hue-rotate(180deg) !important; ' +
        'background: #111 !important; }' +
        'img, video, picture, svg, canvas, iframe, ' +
        '[style*="background-image"] { ' +
        'filter: invert(100%) hue-rotate(180deg) !important; }'
    ));

    (document.head || document.documentElement).appendChild(style);
}());
