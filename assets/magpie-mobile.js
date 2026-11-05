/* ---- the status pill: swipe it sideways to clear it ---------------------
 * app.js's status() writes errors, warnings and confirmations into #status
 * and clears it itself after a few seconds; an error stays eight. A sideways
 * swipe clears it at once, the way Android notifications go; a short drag
 * springs back.
 * With a dialog open the pill floats over it (.lift) but still lives in the
 * footer, and modalBackground() has made the footer inert: no touch reaches
 * the pill, it falls through to the dialog beneath. So the finger is matched
 * against the pill's box rather than the event's target, on window, capture,
 * and the click that ends a swipe is swallowed before the dialog sees it.
 * It runs first and in its own try, so nothing later in this file can keep
 * it from being installed.
 */
try {
  (function () {
    if (window.__magpieSwipeStatus3) {
      return;
    }
    window.__magpieSwipeStatus3 = true;
    var s = null, x0 = 0, y0 = 0, dx = 0, dragging = false, decided = false, eatClickUntil = 0;
    function pillAt(x, y) {
      var el = document.getElementById('status');
      if (!el || !el.textContent) {
        return null;
      }
      var r = el.getBoundingClientRect();
      if (!r.width || !r.height) {
        return null;
      }
      var pad = 6;
      return x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad ? el : null;
    }
    function lift(el) {
      return el.classList.contains('lift') ? 'translateX(-50%) ' : '';
    }
    function clear(el) {
      el.textContent = '';
      el.title = '';
      el.className = 'status';
      el.style.transition = '';
      el.style.transform = '';
      el.style.opacity = '';
      el.style.animation = '';
    }
    window.addEventListener('touchstart', function (e) {
      s = null;
      if (e.touches.length !== 1) {
        return;
      }
      var t = e.touches[0];
      var el = pillAt(t.clientX, t.clientY);
      if (!el) {
        return;
      }
      s = el;
      x0 = t.clientX;
      y0 = t.clientY;
      dx = 0;
      dragging = false;
      decided = false;
    }, { capture: true, passive: true });
    window.addEventListener('touchmove', function (e) {
      if (!s || e.touches.length !== 1) {
        return;
      }
      var t = e.touches[0];
      var mx = t.clientX - x0, my = t.clientY - y0;
      if (!decided) {
        if (Math.abs(mx) < 6 && Math.abs(my) < 6) {
          return;
        }
        decided = true;
        dragging = Math.abs(mx) >= Math.abs(my);
        if (!dragging) {
          s = null;
          return;
        }
        s.style.transition = 'none';
        s.style.animation = 'none';
        var sel = window.getSelection && window.getSelection();
        if (sel) {
          sel.removeAllRanges();
        }
      }
      dx = mx;
      s.style.transform = lift(s) + 'translateX(' + dx + 'px)';
      s.style.opacity = String(Math.max(0.15, 1 - Math.abs(dx) / 220));
      if (e.cancelable) {
        e.preventDefault();
      }
      e.stopPropagation();
    }, { capture: true, passive: false });
    function end(e) {
      if (!s) {
        return;
      }
      var el = s;
      s = null;
      if (!dragging) {
        return;
      }
      dragging = false;
      eatClickUntil = Date.now() + 500;
      e.stopPropagation();
      if (e.type === 'touchend' && Math.abs(dx) > 60) {
        el.style.transition = 'transform .16s ease, opacity .16s ease';
        el.style.transform = lift(el) + 'translateX(' + (dx > 0 ? 480 : -480) + 'px)';
        el.style.opacity = '0';
        setTimeout(function () { clear(el); }, 170);
      } else {
        el.style.transition = 'transform .18s ease, opacity .18s ease';
        el.style.transform = '';
        el.style.opacity = '';
      }
    }
    window.addEventListener('touchend', end, { capture: true, passive: true });
    window.addEventListener('touchcancel', end, { capture: true, passive: true });
    // the click a swipe ends in is not a click on what lies under the pill
    window.addEventListener('click', function (e) {
      if (Date.now() < eatClickUntil) {
        eatClickUntil = 0;
        e.stopPropagation();
        e.preventDefault();
      }
    }, true);
  })();
} catch (err) {}

/* magpie on the phone — the page's own tabs, made to behave in a narrow
 * screen. The row slides sideways (magpie-mobile.css draws the fade), so this
 * only says which edge still holds tabs and brings the open one into view
 * when the page changes. It also watches Plugins: the shell says of every
 * build whether it packed Bun (window.magpieBun), and a build that did not
 * cannot install or run an external plugin, so that page gets one notice and
 * those buttons are left inert. A build that carries Bun — or says nothing at
 * all, because a flag that never arrived proves nothing — is left untouched.
 * It is injected after every load, beside the stylesheet, and does nothing if
 * the page is not magpie's own.
 */
(function () {
  // One schedule for both jobs: a frame at most, and only if something
  // changed (the page redraws itself as its lists come in, in batches).
  var queued = false;
  var due = [];
  function later(fn) {
    due.push(fn);
    if (queued) {
      return;
    }
    queued = true;
    requestAnimationFrame(function () {
      queued = false;
      var run = due;
      due = [];
      for (var i = 0; i < run.length; i++) {
        run[i]();
      }
    });
  }

  /* ---- Plugins: softened only where the build carries no Bun ------------ */

  // `#view-plugins` is the whole Plugins page (index.html) and `#modal .dialog`
  // holds its detail sheet (app.js's openModal); both are drawn and redrawn by
  // upstream, so the work is done in one pass and a second pass over a
  // settled tree finds nothing to change — no loop, however often the page
  // redraws (MagpieGateway's WebView runs the same app.js a browser has).
  var view = document.getElementById('view-plugins');
  // the shell paints the skin more than once on one page (MainActivity calls
  // it on commit and again on finish): the first run keeps the watching, a
  // later one only asks it for a pass, in case the flag came out differently
  if (view && view.getAttribute('data-magpie-bun-watch') === '1') {
    view.dispatchEvent(new CustomEvent('magpie-mobile:repaint'));
  } else if (view) {
    view.setAttribute('data-magpie-bun-watch', '1');
    // The external-plugin operations as plugins.js makes them: a card's one
    // button (actionFor) and its skeleton, the Installed tab's footer
    // (drawInstalled), the detail sheet's head (detail), and the whole
    // "Have one in mind?" form (manual). Built-in's own switches are a
    // lib-switch inside #builtinList and are not touched.
    var ACTIONS = [
      '#view-plugins .pm-body .pm-act',       // Install, Update, Move here, Coming soon, the busy spinner
      '#view-plugins .pm-foot .pm-check',     // Check for updates
      '#view-plugins .pm-manual button',      // Install, and the folder picker
      '#view-plugins .pm-row .val button',    // Update, Move, Switch off/on, Remove
      '.pm-dact .pm-act',                     // the detail sheet's one button
    ].join(',');

    // what the page does when its build has no Bun: one action button at a
    // time. What the button carried before is kept on it, so paint() below
    // can put it back when the flag turns out to say otherwise.
    function mark(el) {
      if (el.classList.contains('mg-no-bun')) {
        return;
      }
      el.setAttribute('data-magpie-tabindex', el.getAttribute('tabindex') || '');
      el.classList.add('mg-no-bun');
      el.setAttribute('aria-disabled', 'true');
      // out of the tab order: a keyboard is the one way aria-disabled wouldn't
      // hold, and this page is read with a finger
      el.setAttribute('tabindex', '-1');
    }

    function notice() {
      var p = document.createElement('p');
      p.className = 'mg-no-bun-note';
      p.setAttribute('data-plugin-note', '1');
      p.setAttribute('role', 'note');
      var b = document.createElement('b');
      b.textContent = '本次构建未内置插件运行时';
      var span = document.createElement('span');
      span.textContent = '外部插件是 npm 包，靠 Bun 运行；这个安装包没有把它一起打包，所以安装、更新和移除在这里用不了。重新打包时带上 Bun 即可启用，内置插件不受影响。';
      p.append(b, span);
      return p;
    }

    // a redraw (draw, drawBody) replaces the body whole, and replaceKeeping
    // drops anything it doesn't know: the notice is put back by asking the
    // body that is in the page now, never by a flag that can outlive it. It
    // sits first in the body, under the tabs
    function ensure() {
      var body = view.querySelector('.pm-body');
      if (body && body.firstElementChild !== view.querySelector('.mg-no-bun-note')) {
        body.insertBefore(notice(), body.firstChild);
      }
      var acts = view.querySelectorAll(ACTIONS);
      for (var i = 0; i < acts.length; i++) {
        mark(acts[i]);
      }
    }

    function sheet() {
      var box = document.getElementById('modal');
      if (!box || box.hidden) {
        return;
      }
      var dialog = box.querySelector('.dialog');
      // the plugin detail sheet is the one that offers the card's own button
      if (!dialog || !dialog.querySelector('.pm-dact')) {
        return;
      }
      if (!dialog.querySelector('.mg-no-bun-note')) {
        var p = notice();
        p.className = 'mg-no-bun-note mg-no-bun-sheet';
        var head = dialog.querySelector('.pm-dhead');
        if (head) {
          head.after(p);
        } else {
          dialog.insertBefore(p, dialog.firstChild);
        }
      }
      var acts = dialog.querySelectorAll(ACTIONS);
      for (var i = 0; i < acts.length; i++) {
        mark(acts[i]);
      }
    }

    // every button the notice touched, back to what upstream left: the class
    // the stylesheet hangs off, the aria, and the tabindex that was there (or
    // wasn't) before — the notice goes with them
    function restore() {
      var notes = document.querySelectorAll('.mg-no-bun-note');
      for (var n = 0; n < notes.length; n++) {
        notes[n].parentNode.removeChild(notes[n]);
      }
      var acts = document.querySelectorAll('.mg-no-bun');
      for (var a = 0; a < acts.length; a++) {
        var el = acts[a];
        el.classList.remove('mg-no-bun');
        el.removeAttribute('aria-disabled');
        var was = el.getAttribute('data-magpie-tabindex');
        if (was) {
          el.setAttribute('tabindex', was);
        } else {
          el.removeAttribute('tabindex');
        }
        el.removeAttribute('data-magpie-tabindex');
      }
    }

    // false is the one value that says "this build packed no Bun"; true says
    // it did, and undefined says the shell never told — and a page left as
    // upstream wrote it is better than one guessing that nothing can run
    var softened = false;
    var paint = function () {
      if (window.magpieBun === false) {
        ensure();
        sheet();
        softened = true;
      } else if (softened) {
        restore();
        softened = false;
      }
    };
    var paintLater = function () {
      later(paint);
    };
    paint();
    // childList alone: the marks are attributes, which this observer doesn't
    // watch, so setting them can't wake it again
    new MutationObserver(paintLater).observe(view, { childList: true, subtree: true });
    // the sheet lives in #modal (index.html), outside the page's own element,
    // and is emptied and refilled as the page draws: watched for the same
    // reason, once
    var box = document.getElementById('modal');
    if (box) {
      new MutationObserver(paintLater).observe(box, { childList: true, subtree: true });
    }
    // the second paint of the skin on a page asks through this, and the page
    // can also be redrawn at a size the tables don't read or before the
    // webfont settles — cheap chances, taken by the nav row too, to look at
    // the flag again
    view.addEventListener('magpie-mobile:repaint', paintLater);
    window.addEventListener('resize', paintLater);
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(paintLater);
    }

    // The shell writes window.magpieBun, and it can write it after this has
    // run (MainActivity paints the skin on commit, when the page is still
    // empty, and again on finish). An assignment is how it is written: a
    // plain property can be turned into one that notices at once. Anything
    // the shell froze or defines itself is left alone — the slow tick below
    // reads the flag as it stands, once a second, and does nothing else.
    var seen = window.magpieBun;
    function flag() {
      var now = window.magpieBun;
      if (now !== seen) {
        seen = now;
        paintLater();
      }
    }
    try {
      var desc = Object.getOwnPropertyDescriptor(window, 'magpieBun');
      if (!desc || (desc.configurable && 'value' in desc)) {
        var held = desc ? desc.value : undefined;
        Object.defineProperty(window, 'magpieBun', {
          configurable: true,
          enumerable: true,
          get: function () {
            return held;
          },
          set: function (v) {
            held = v;
            flag();
          },
        });
      }
    } catch (e) {
      // the shell's own definition stands; the tick still reads it
    }
    setInterval(flag, 1000);
  }

  var nav = document.getElementById('nav');
  if (!nav || nav.getAttribute('data-magpie-tabs') === '1') {
    watchAndroid();
    return;
  }
  nav.setAttribute('data-magpie-tabs', '1');

  var active = null;

  function edges() {
    var slack = nav.scrollWidth - nav.clientWidth;
    if (slack <= 1) {
      nav.removeAttribute('data-edge');
      return;
    }
    var atStart = nav.scrollLeft <= 1;
    var atEnd = nav.scrollLeft >= slack - 1;
    nav.setAttribute('data-edge', atStart && atEnd ? '' : atStart ? 'end' : atEnd ? 'start' : 'both');
  }

  function reveal(tab) {
    if (!tab || nav.clientWidth === 0) {
      return;
    }
    var row = nav.getBoundingClientRect();
    var box = tab.getBoundingClientRect();
    var left = box.left - row.left + nav.scrollLeft;
    var margin = 14;
    if (left - margin < nav.scrollLeft) {
      nav.scrollTo({ left: left - margin, behavior: 'smooth' });
    } else if (left + box.width + margin > nav.scrollLeft + nav.clientWidth) {
      nav.scrollTo({ left: left + box.width + margin - nav.clientWidth, behavior: 'smooth' });
    }
  }

  function sync() {
    var on = nav.querySelector('button.on');
    if (on !== active) {
      active = on;
      reveal(on);
    }
    edges();
  }

  var syncLater = function () {
    later(sync);
  };

  nav.addEventListener('scroll', edges, { passive: true });
  window.addEventListener('resize', syncLater);
  new MutationObserver(syncLater).observe(nav, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['class'],
  });
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(syncLater);
  }
  sync();
  watchAndroid();

  // ---- Plugins: the one package Bun's Android build cannot unpack ----------

  /*
   * `zcode-provider` makes Bun's Android build die while it unpacks the
   * package ("signal: bad system call", exit 159), after it has resolved the
   * tree: nothing is installed, and the page can only show the raw error.
   * The shell says this is the Android one (window.magpieAndroid), so the
   * failure is explained where it happens — the manual install form and the
   * status line — and every other package is left as upstream runs it.
   */
  function watchAndroid() {
    var plugins = document.getElementById('view-plugins');
    if (!plugins || !window.magpieAndroid) {
      return;
    }
    var NOTE = 'Bun 的安卓版解包这个插件时会崩溃（bad system call），所以它装不上；' +
      '这不是 magpie 的问题，换一个插件即可。';

    function named(text) {
      return /(^|[\s"'])zcode-provider(@[^\s"']*)?($|[\s"'])/i.test(text || '');
    }

    function explain() {
      var manual = plugins.querySelector('.pm-manual');
      if (manual && !manual.querySelector('.mg-zcode-note')) {
        var input = manual.querySelector('.pm-minput');
        if (input && named(input.value)) {
          var note = document.createElement('p');
          note.className = 'mg-zcode-note';
          note.setAttribute('role', 'note');
          note.textContent = NOTE;
          manual.append(note);
        }
      }
      var status = document.getElementById('status');
      if (status && !status.querySelector('.mg-zcode-note') && named(status.textContent)) {
        var said = document.createElement('span');
        said.className = 'mg-zcode-note';
        said.textContent = ' ' + NOTE;
        status.append(said);
      }
    }

    var due = false;
    function soon() {
      if (due) {
        return;
      }
      due = true;
      requestAnimationFrame(function () {
        due = false;
        explain();
      });
    }
    new MutationObserver(soon).observe(document.body, {
      childList: true,
      subtree: true,
      characterData: true,
    });
    plugins.addEventListener('input', soon, true);
    soon();
  }
})();

