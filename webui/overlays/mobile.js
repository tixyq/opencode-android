// oc-mobile: visible row menu buttons (no long-press needed) on small screens.
(function () {
  function ensureRowButtons() {
    var rows = document.querySelectorAll('button:has([data-slot="home-session-labels"])');
    for (var i = 0; i < rows.length; i++) {
      (function (row) {
        if (row.getAttribute('data-oc-menu')) return;
        row.setAttribute('data-oc-menu', '1');
        if (window.getComputedStyle(row).position === 'static') {
          row.style.position = 'relative';
        }
        var b = document.createElement('button');
        b.setAttribute('type', 'button');
        b.setAttribute('aria-label', 'menu');
        b.textContent = '···';
        b.style.cssText = 'position:absolute;right:6px;top:50%;transform:translateY(-50%);'
          + 'width:32px;height:32px;border-radius:8px;border:none;'
          + 'background:transparent;color:var(--v2-icon-icon-muted,#808080);'
          + 'font-size:16px;line-height:1;cursor:pointer;';
        b.addEventListener('click', function (e) {
          e.stopPropagation();
          e.preventDefault();
          var r = row.getBoundingClientRect();
          row.dispatchEvent(new MouseEvent('contextmenu', {
            bubbles: true,
            cancelable: true,
            clientX: r.right - 12,
            clientY: r.top + r.height / 2,
          }));
        });
        row.appendChild(b);
      })(rows[i]);
    }
  }
  // File-tree shadow scrollers: native-feeling touch scroll.
  var TREE_CSS = '[data-file-tree-virtualized-scroll]{touch-action:pan-y !important;'
    + 'overscroll-behavior:contain;-webkit-overflow-scrolling:touch;}';
  var lastTreeFix = 0;
  function fixTreeScroll() {
    var now = Date.now();
    if (now - lastTreeFix < 2000) return;
    if (!document.querySelector('.directory-picker-browser')) return;
    lastTreeFix = now;
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var root = null;
      try {
        root = el.shadowRoot;
      } catch (e) {
        continue;
      }
      if (!root || root.getAttribute('data-oc-treefix')) continue;
      var scroller = null;
      try {
        scroller = root.querySelector('[data-file-tree-virtualized-scroll]');
      } catch (e) {
        continue;
      }
      if (!scroller) continue;
      try {
        var style = document.createElement('style');
        style.textContent = TREE_CSS;
        root.appendChild(style);
        root.setAttribute('data-oc-treefix', '1');
      } catch (e) { /* ignore */ }
    }
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      try {
        ensureRowButtons();
        fixTreeScroll();
        fixAbout();
      } catch (e) { /* never break the app */ }
    }, 300);
  }
  // Settings > About: keep only the logo, with "version n" under it.
  // Hiding is CSS-first (no flash); JS only injects the version line.
  // n comes strictly from the running server: /oc-version (native,
  // same origin, no auth), else authed /api/info using the seeded
  // server entry, else the stock version line already in the DOM.
  var aboutRetries = 0;
  var aboutSeen = null;
  var aboutServerVer = null;
  // idle: server not asked yet; pending: fetch in flight (DOM fallback
  // must wait, it would otherwise win the race with stale text);
  // done: server version known; failed: use DOM fallback.
  var aboutServerState = 'idle';
  function ocServerCreds() {
    try {
      var raw = localStorage.getItem('opencode.global.dat:server');
      if (!raw) return null;
      var o = JSON.parse(raw);
      var list = o && o.list;
      if (!list || !list.length) return null;
      for (var i = 0; i < list.length; i++) {
        var e = list[i];
        if (e && e.type === 'http' && e.http && e.http.url && e.http.password) {
          return {
            url: String(e.http.url).replace(/\/+$/, ''),
            password: String(e.http.password)
          };
        }
      }
    } catch (e) { /* ignore */ }
    return null;
  }
  function askServerVersion() {
    // 1. Native endpoint (exact managed-server version).
    aboutServerState = 'pending';
    try {
      fetch('/oc-version', {headers: {Accept: 'text/plain'}})
        .then(function (r) { return r.ok ? r.text() : ''; })
        .then(function (t) {
          t = String(t || '').trim();
          if (/^\d+\.\d+\.\d+$/.test(t)) {
            aboutServerVer = t;
            aboutServerState = 'done';
            try { fixAbout(); } catch (e) { /* ignore */ }
            return;
          }
          askApiVersion();
        })
        .catch(function () { askApiVersion(); });
    } catch (e) { askApiVersion(); }
  }
  function askApiVersion() {
    // 2. Authenticated /api/info with the seeded server credentials.
    var creds = null;
    try {
      creds = ocServerCreds();
    } catch (e) { creds = null; }
    if (!creds) {
      aboutServerState = 'failed';
      try { fixAbout(); } catch (e) { /* ignore */ }
      return;
    }
    try {
      fetch(creds.url + '/api/info', {headers: {
        Accept: 'application/json',
        Authorization: 'Basic ' + btoa('opencode:' + creds.password)
      }})
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (j) {
          var v = j && String(j.version || '').trim();
          if (/^\d+\.\d+\.\d+$/.test(v)) {
            aboutServerVer = v;
            aboutServerState = 'done';
          } else {
            aboutServerState = 'failed';
          }
          try { fixAbout(); } catch (e) { /* ignore */ }
        })
        .catch(function () {
          aboutServerState = 'failed';
          try { fixAbout(); } catch (e) { /* ignore */ }
        });
    } catch (e) {
      aboutServerState = 'failed';
      try { fixAbout(); } catch (e) { /* ignore */ }
    }
  }
  function fixAbout() {
    try {
      var content = document.querySelector('.settings-about-content');
      if (!content) return;
      if (content.getAttribute('data-oc-about')) return;
      if (aboutSeen !== content) {
        aboutSeen = content;
        aboutRetries = 0;
        aboutServerState = 'idle';
        aboutServerVer = null;
      }
      var logo = content.querySelector('svg.settings-about-wordmark');
      if (!logo) return;
      if (aboutServerState === 'idle') {
        aboutServerState = 'pending';
        askServerVersion();
      }
      var ver = aboutServerVer;
      if (!ver && aboutServerState === 'failed') {
        var verEl = content.querySelector('.settings-about-intro > p:first-child');
        var m = verEl ? String(verEl.textContent || '').match(/\d+\.\d+\.\d+/) : null;
        if (m) ver = m[0];
      }
      if (!ver) {
        // Version text arrives async after the logo; retry briefly.
        if (aboutRetries < 10) {
          aboutRetries++;
          setTimeout(function () {
            try { fixAbout(); } catch (e) { /* ignore */ }
          }, 1000);
        }
        return;
      }
      var p = content.querySelector('p.oc-about-version');
      if (!p) {
        p = document.createElement('p');
        p.className = 'oc-about-version';
        logo.parentNode.insertBefore(p, logo.nextSibling);
      }
      p.textContent = 'version ' + ver;
      content.setAttribute('data-oc-about', '1');
    } catch (e) { /* never break the app */ }
  }

  function observe() {
    // Hide home menus synchronously (no debounce) to avoid flicker.
    try {
      hideHomeProjectMenu();
    } catch (e) { /* never break the app */ }
    // About fix runs sync too: CSS already hides, JS only adds the line.
    try {
      fixAbout();
    } catch (e) { /* never break the app */ }
    schedule();
  }
  try {
    new MutationObserver(observe).observe(document.documentElement, {
      childList: true,
      subtree: true,
    });
  } catch (e) { /* ignore */ }
  try {
    hideHomeProjectMenu();
  } catch (e) { /* ignore */ }
  schedule();

  // Hide the row menu trigger on the home project only (it holds Close;
  // other projects keep it). The home row label is its folder basename.
  function hideHomeProjectMenu() {
    var triggers = document.querySelectorAll('[data-action="home-project-menu"]');
    for (var i = 0; i < triggers.length; i++) {
      (function (t) {
        if (t.getAttribute('data-oc-hidden')) return;
        var row = null;
        try {
          row = t.closest('div:has([data-slot="home-row-label"])');
        } catch (e) {
          return;
        }
        if (!row) return;
        var label = row.querySelector('[data-slot="home-row-label"]');
        if (label && label.textContent.trim() === 'home') {
          t.style.display = 'none';
          t.setAttribute('data-oc-hidden', '1');
        }
      })(triggers[i]);
    }
  }
})();
