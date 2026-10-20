'use strict';

/**
 * Arktos Mobile — built-in userscript host (ENG05).
 *
 * Two channels:
 *  * App -> page: the app pushes {type:"sync", bootstrap} over a
 *    persistent native port; the bootstrap (the same script the WebView
 *    backend injects via addDocumentStartJavaScript) is registered as
 *    a document-start content script on <all_urls>.
 *  * Page -> app: the bootstrap calls runtime.sendNativeMessage
 *    itself — GeckoView routes content-script native messages to the
 *    per-session message delegate, keeping the real page URL on the
 *    sender for the app's SEC06 origin checks. No relay here.
 */

const NATIVE_APP = 'browser';

let registered = null;
let pendingRegister = Promise.resolve();

function register(bootstrap) {
  // Serialize re-registration: unregister the previous content script
  // before installing the new set so two can never overlap.
  pendingRegister = pendingRegister
    .catch(() => {})
    .then(() => (registered ? registered.unregister() : undefined))
    .catch(() => {})
    .then(() => {
      registered = null;
      if (!bootstrap) return;
      return browser.contentScripts
        .register({
          matches: ['<all_urls>'],
          js: [{ code: bootstrap }],
          runAt: 'document_start',
          allFrames: true,
          matchAboutBlank: true
        })
        .then((handle) => {
          registered = handle;
        })
        .catch(() => {});
    });
}

// Script-set pushes from the app. (GM ops travel the other channel —
// content-script sendNativeMessage straight to the session delegate,
// no background relay so sender.url stays the page's.)
const port = browser.runtime.connectNative(NATIVE_APP);
port.onMessage.addListener((m) => {
  if (m && m.type === 'sync') register(m.bootstrap || '');
});
port.onDisconnect.addListener(() => {
  // The app tears the port down on shutdown only; nothing to do — the
  // next engine start reconnects and re-syncs.
});
