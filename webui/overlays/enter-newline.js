// oc-mobile: Enter in composer = newline, send only via the Send button.
// Runs before the app bundle (classic script in <head>), listens on window
// in the capture phase so it preempts the app's own Enter-to-send handler.
(function () {
  function inComposer(node) {
    return !!(
      node &&
      node instanceof HTMLElement &&
      node.closest('[data-component="composer"]')
    );
  }

  function menuOpen() {
    // Kobalte menus/popovers render with these roles only while open.
    return !!document.querySelector('[role="menu"],[role="listbox"]');
  }

  function insertNewline() {
    try {
      if (document.execCommand('insertText', false, '\n')) return true;
    } catch (err) {
      /* fall through to Range API */
    }
    try {
      var sel = window.getSelection();
      if (sel && sel.rangeCount > 0) {
        var r = sel.getRangeAt(0);
        r.deleteContents();
        var nl = document.createTextNode('\n');
        r.insertNode(nl);
        r.setStartAfter(nl);
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        var el = r.startContainer;
        var editable = el instanceof HTMLElement ? el : el.parentElement;
        if (editable) {
          editable.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
        }
        return true;
      }
    } catch (err) {
      /* ignore */
    }
    return false;
  }

  window.addEventListener(
    'keydown',
    function (e) {
      if (e.key !== 'Enter' && e.keyCode !== 13) return;
      if (e.isComposing) return;
      if (!inComposer(e.target)) return;
      if (menuOpen()) return; // let open menus ( @ / commands / model ) use Enter
      e.preventDefault();
      e.stopPropagation();
      insertNewline();
    },
    { capture: true },
  );
})();
