/* Returns the first <meta name="theme-color"> content value for the
 * toolbar tint, or '' when the page declares none. */
(function () {
    'use strict';

    var metas = document.getElementsByTagName('meta');
    for (var i = 0; i < metas.length; i += 1) {
        var tag = metas[i].getAttribute('name');
        if (tag !== null && tag.toLowerCase() === 'theme-color') {
            return metas[i].getAttribute('content') || '';
        }
    }

    return '';
}());
