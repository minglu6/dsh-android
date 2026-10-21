// Classic bootstrap: module-load/CSP failures must remain observable to native UI.
(() => {
  let error;
  let ready = false;
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
        return { ready: true, ...window.codePreview.status() };
      },
      apply(options, content) {
        if (error) return this.status();
        try {
          if (!ready) throw new Error('Renderer did not initialize.');
          window.codePreview.setOptions(options);
          if (content) content();
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
