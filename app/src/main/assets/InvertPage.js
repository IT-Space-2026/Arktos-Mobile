/* Manual "invert page" toggle: inverts the whole page and re-inverts
 * media so images stay recognizable. Idempotent by style id — unlike
 * the old script this does not rely on a page-visible window.counter
 * global (any page could have set it). */
(function () {
    'use strict';

    var STYLE_ID = 'arktos-invert-page';
    if (document.getElementById(STYLE_ID)) {
        return;
    }

    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.type = 'text/css';
    style.appendChild(document.createTextNode(
        'html { filter: invert(100%) hue-rotate(180deg) !important; }' +
        'img, video, picture, svg, canvas, ' +
        '[style*="background-image"] { ' +
        'filter: invert(100%) hue-rotate(180deg) !important; }'
    ));

    (document.head || document.documentElement).appendChild(style);
}());
