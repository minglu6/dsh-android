// Classic bootstrap: module-load/CSP failures must remain observable to native UI.
(() => {
  let error;
  let ready = false;
  let font;
  let fontsReady = true;
  function fail(reason) { error ??= `Code preview: ${reason?.message ?? String(reason)}`; }
  window.addEventListener('error', event => {
    fail(event.error ?? event.message ?? `Unable to load ${event.target?.src ?? 'resource'}`);
  }, true);
  window.addEventListener('unhandledrejection', event => fail(event.reason));
  document.addEventListener('securitypolicyviolation', event => {
    fail(`Blocked ${event.violatedDirective}: ${event.blockedURI}`);
  });
  Object.defineProperty(window, 'codePreviewBridge', {
    value: {
      status() {
        if (error) return { ready: false, state: 'error', error };
        if (!ready) return { ready: false, state: 'loading' };
        const status = window.codePreview.status();
        return { ready: true, ...status, state: status.state === 'ready' && !fontsReady ? 'rendering' : status.state };
      },
      apply(options, content) {
        if (error) return this.status();
        try {
          if (!ready) throw new Error('Renderer did not initialize.');
          window.codePreview.setOptions(options);
          if (content) content();
          const style = getComputedStyle(document.body);
          const requestedFont = `${style.fontSize} ${style.fontFamily}`;
          if (font !== requestedFont) {
            font = requestedFont;
            fontsReady = false;
            // An empty page can report fonts.status === 'loaded' before its first layout.
            // Request the selected face explicitly, then wait for its settled layout.
            document.fonts.load(requestedFont).then(() => document.fonts.ready).then(() => {
              if (font === requestedFont) fontsReady = true;
            }).catch(fail);
          }
        } catch (reason) { fail(reason); }
        return this.status();
      },
    },
    writable: false,
  });
  import('./preview.js').then(() => {
    if (!window.codePreview || !document.getElementById('code')) throw new Error('Renderer did not initialize.');
    ready = true;
  }).catch(fail);
})();
