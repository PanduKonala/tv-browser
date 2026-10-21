// Runs at document start in every frame (WebViewCompat.addDocumentStartJavaScript).
(function () {
  if (window.__clear) return;
  window.__clear = true;

  // Edge scrolling for the D-pad pointer: scroll the innermost scrollable box under the pointer,
  // falling back to the page. Coordinates arrive as fractions of the viewport.
  if (window.top === window) {
    window.__clearScroll = function (fx, fy, fdx, fdy) {
      var dx = fdx * innerWidth, dy = fdy * innerHeight;
      var el = document.elementFromPoint(fx * innerWidth, fy * innerHeight);
      for (; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
        var s = getComputedStyle(el);
        if (dy && /auto|scroll|overlay/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 1) {
          var t = el.scrollTop; el.scrollTop += dy; if (el.scrollTop !== t) return true;
        }
        if (dx && /auto|scroll|overlay/.test(s.overflowX) && el.scrollWidth > el.clientWidth + 1) {
          var l = el.scrollLeft; el.scrollLeft += dx; if (el.scrollLeft !== l) return true;
        }
      }
      var sy = scrollY, sx = scrollX;
      window.scrollBy(dx, dy);
      return scrollY !== sy || scrollX !== sx;
    };
    window.__clearMedia = function (cmd) { media(cmd); };
  }

  // Remote media keys: act on this frame's playing (or first) video/audio, then pass the command
  // down to child frames, since most players sit in an iframe the app can't script directly.
  function media(cmd) {
    var all = document.querySelectorAll('video,audio'), v = null;
    for (var i = 0; i < all.length; i++) if (!all[i].paused) { v = all[i]; break; }
    if (!v) v = all[0];
    if (v) {
      if (cmd && cmd.seek !== undefined) { v.currentTime = cmd.seek; v.pause(); }
      // The remote's play key is the user pressing play (see the autoplay hold below).
      else if (cmd === 'toggle') { if (v.paused) { v.__clearOk = true; v.play(); } else v.pause(); }
      else if (cmd === 'play') { v.__clearOk = true; v.play(); }
      else if (cmd === 'pause') v.pause();
      else if (isFinite(v.duration)) v.currentTime = Math.max(0, Math.min(v.duration, v.currentTime + cmd));
    }
    for (var f = 0; f < window.frames.length; f++) {
      try { window.frames[f].postMessage({ __clearMedia: cmd }, '*'); } catch (e) {}
    }
  }
  window.addEventListener('message', function (e) {
    if (e.data && e.data.__clearMedia !== undefined && e.source === window.parent) media(e.data.__clearMedia);
  });

  // Cost of this script, read by the app for its ClearPerf log line.
  var S0 = window.__clearStats = { bridgeMs: 0, bridgeCalls: 0, scanMs: 0, scanned: 0, appends: 0, cssKB: 0, previewsHeld: 0,
                                   longMs: 0, longN: 0, lcp: 0 };
  var DEBUG = false;
  try { DEBUG = !!(window.ClearBridge && ClearBridge.debug()); } catch (e) {}
  if (DEBUG && window.top === window && window.PerformanceObserver) {
    try {
      new PerformanceObserver(function (l) {
        l.getEntries().forEach(function (e) { S0.longMs += e.duration; S0.longN++; });
      }).observe({ type: 'longtask', buffered: true });
      new PerformanceObserver(function (l) {
        var e = l.getEntries(); if (e.length) S0.lcp = e[e.length - 1].startTime;
      }).observe({ type: 'largest-contentful-paint', buffered: true });
    } catch (e) {}
  }

  // Streaming: keep adaptive players at or under 1080p / 60 fps. Above that (1440p, 4K, 120 fps)
  // these TVs can't decode in time, so a player that picks it on a fast connection just stalls.
  // Everything at 1080p and below plays exactly as the site serves it.
  var MAXH = 1080, MAXFPS = 60;
  function capIndex(levels, h, fps) {
    var best = -1, over = false;
    for (var i = 0; i < levels.length; i++) {
      var lh = h(levels[i]);
      if (lh > MAXH || (fps && fps(levels[i]) > MAXFPS)) over = true;
      else if (lh && (best < 0 || lh >= h(levels[best]))) best = i;
    }
    return over ? best : -1;
  }
  // hls.js (most streaming sites): wrap the class whenever a page puts it on window.
  function wrapHls(H) {
    if (typeof H !== 'function' || H.__clear) return H;
    try {
      // RAM: hls.js keeps everything already watched (back buffer: unlimited) and may buffer up to
      // 10 minutes ahead. On a 1 GB TV that pushes the system into swap mid-film, which stalls
      // playback, so default to a lean buffer. Values the site sets itself still win.
      var LEAN = { backBufferLength: 10, maxBufferLength: 20, maxMaxBufferLength: 40, maxBufferSize: 30 * 1000 * 1000 };
      var W = class extends H {
        constructor(cfg) {
          var c = Object.assign({}, cfg || {});
          for (var k in LEAN) if (!(k in c)) c[k] = LEAN[k];
          super(c);
          var self = this;
          function cap() {
            var i = capIndex(self.levels || [], function (l) { return l.height; },
                             function (l) { return parseFloat(l.frameRate || (l.attrs && l.attrs['FRAME-RATE'])) || 0; });
            if (i >= 0) self.autoLevelCapping = i;
          }
          try { self.on(H.Events.MANIFEST_PARSED, cap); self.on(H.Events.LEVELS_UPDATED, cap); } catch (e) {}
        }
      };
      W.__clear = true;
      return W;
    } catch (e) { return H; }
  }
  // JW Player bundles its own HLS engine; pick the best level at or under the cap once levels are known.
  function wrapJw(J) {
    if (typeof J !== 'function' || J.__clear) return J;
    var P = new Proxy(J, {
      apply: function (t, self, args) {
        var p = Reflect.apply(t, self, args);
        if (p && p.on && !p.__clear) {
          p.__clear = true;
          p.on('levels', function (e) {
            var lv = e.levels || [];
            if (e.currentQuality !== 0 || !lv.length || !/auto/i.test(lv[0].label || '')) return;
            var i = capIndex(lv, function (l) { return l.height || parseInt(l.label, 10) || 0; });
            if (i > 0) p.setCurrentQuality(i);
          });
        }
        return p;
      },
      get: function (t, k) { return k === '__clear' ? true : t[k]; }
    });
    return P;
  }
  function hook(name, wrap) {
    var val = window[name] ? wrap(window[name]) : undefined;
    try {
      Object.defineProperty(window, name, {
        configurable: true, enumerable: true,
        get: function () { return val; },
        set: function (v) { val = wrap(v); }
      });
    } catch (e) {}
  }
  hook('Hls', wrapHls);
  hook('jwplayer', wrapJw);
  // Shaka / dash.js style players ask MediaCapabilities; tell them what's over the cap won't be smooth.
  try {
    var mc = navigator.mediaCapabilities, di = mc && mc.decodingInfo && mc.decodingInfo.bind(mc);
    if (di) mc.decodingInfo = function (cfg) {
      return di(cfg).then(function (r) {
        if (!cfg || !cfg.video || !(cfg.video.height > MAXH || cfg.video.framerate > MAXFPS)) return r;
        return { supported: r.supported, smooth: false, powerEfficient: false,
                 keySystemAccess: r.keySystemAccess, configuration: r.configuration };
      });
    };
  } catch (e) {}

  var B = window.ClearBridge;
  if (!B) return;

  // The app's own annoyance fixes: store a site's "notice dismissed" flag before its scripts look
  // for it, as its close button would (never over a value the site stored itself).
  try {
    var fixes = B.storage(location.hostname);
    if (fixes) JSON.parse(fixes).forEach(function (kv) {
      if (localStorage.getItem(kv[0]) === null) localStorage.setItem(kv[0], kv[1]);
    });
  } catch (e) {}

  // Lighter rendering for TV GPUs: frosted-glass blur (backdrop-filter) is recomputed for every
  // scroll frame and is the biggest single cost on these chips. Without it, translucent bars stay
  // translucent, just not blurred.
  var lite = true;
  try { lite = B.liteEffects(); } catch (e) {}
  if (lite) {
    var ls = document.createElement('style');
    // No frosted glass, and no endless decorative pulses (Tailwind's animate-ping/pulse "live" dots):
    // each keeps the TV redrawing the whole screen. Loading spinners keep spinning.
    ls.textContent = '*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}'
        + '[class*="animate-ping"],[class*="animate-pulse"]{animation:none!important}';
    // New frames can start before their document has an element to attach to: wait for one
    // rather than fail (a throw here would stop everything below in that frame).
    var putLite = function () {
      var root = document.head || document.documentElement;
      if (!root) return false;
      if (!ls.isConnected) root.appendChild(ls);
      return true;
    };
    if (!putLite()) {
      var waitRoot = new MutationObserver(function () { if (putLite()) waitRoot.disconnect(); });
      waitRoot.observe(document, { childList: true, subtree: true });
    }
    document.addEventListener('DOMContentLoaded', putLite);

    // Decorative canvases (animated WebGL shaders or 2D effects behind the page) make a weak TV repaint and
    // recombine the screen 60 times a second even when nothing else moves (one site drew 1,165
    // frames while idle). Hide them, so the page's own background colour shows and the TV goes
    // quiet; their drawing loop keeps running but on a tiny, hidden canvas. Only canvases that
    // cover a large part of the screen *behind* the content and take no clicks: games, maps and
    // players are left alone.
    var realGetContext = HTMLCanvasElement.prototype.getContext;
    var widthProp = Object.getOwnPropertyDescriptor(HTMLCanvasElement.prototype, 'width');
    var heightProp = Object.getOwnPropertyDescriptor(HTMLCanvasElement.prototype, 'height');
    HTMLCanvasElement.prototype.getContext = function (type) {
      var ctx = realGetContext.apply(this, arguments);
      if (ctx && /webgl|2d/i.test(String(type)) && !this.__clearBg) {
        this.__clearBg = 1;
        var c = this;
        setTimeout(function () { try { if (isBackdrop(c)) shrink(c); } catch (e) {} }, 800);
      }
      return ctx;
    };
    var isBackdrop = function (c) {
      if (!c.isConnected) return false;
      var r = c.getBoundingClientRect();
      if (r.width * r.height < innerWidth * innerHeight * 0.25) return false;
      var behind = false;
      for (var el = c; el && el !== document.documentElement; el = el.parentElement) {
        var s = getComputedStyle(el);
        if (s.pointerEvents === 'none' || parseInt(s.zIndex, 10) < 0 || el.getAttribute('aria-hidden') === 'true') behind = true;
        if (s.position === 'fixed' || s.position === 'absolute') return behind;
      }
      return false;
    };
    var shrink = function (c) {
      var scale = 0.25;
      Object.defineProperty(c, 'width', { configurable: true,
        get: function () { return widthProp.get.call(c); },
        set: function (v) { widthProp.set.call(c, Math.max(1, Math.round(v * scale))); } });
      Object.defineProperty(c, 'height', { configurable: true,
        get: function () { return heightProp.get.call(c); },
        set: function (v) { heightProp.set.call(c, Math.max(1, Math.round(v * scale))); } });
      // Apply now; the page's own resize code goes through the same setters from here on.
      c.width = widthProp.get.call(c);
      c.height = heightProp.get.call(c);
      c.style.setProperty('display', 'none', 'important');
      window.dispatchEvent(new Event('resize'));
    };
  }

  // Fetching for the TV player (PagePipe): when a server refuses the TV player, the frame that plays
  // the video downloads the bytes itself, sending exactly what it always sends (its cookies, Origin,
  // Referer), and passes them back as an ArrayBuffer. Large files come in ranged pieces.
  var nativeFetch = window.fetch ? window.fetch.bind(window) : null;
  // Headers the page's own scripts add to requests (players' tokens, X-Session-style keys), by server:
  // fetching for the TV player sends them too.
  var hostHeaders = {};
  var noteHeader = function (url, k, v) {
    if (/^(range|content-type|content-length|accept)$/i.test(k)) return;
    try {
      var h = new URL(url, location.href).host;
      (hostHeaders[h] = hostHeaders[h] || {})[k] = v;
    } catch (e) {}
  };
  // Reports about this frame's video go over the pipe too: there WebView tells the app which frame
  // sent them (the JS bridge can't, so a frame could claim to be another).
  var pipePost = null;
  var pipeReady = function () {
    var P = window.ClearPipe;
    if (!P || !nativeFetch || P.__clear) return !!P;
    P.__clear = true;
    pipePost = function (o) { try { P.postMessage(JSON.stringify(o)); } catch (e) {} };
    P.onmessage = function (ev) {
      var q;
      try { q = JSON.parse(ev.data); } catch (e) { return; }
      var head = function (h) { h.id = q.id; P.postMessage(JSON.stringify(h)); };
      var get = function (cred) {
        var o = { credentials: cred, cache: 'no-store', headers: {} };
        try { Object.assign(o.headers, hostHeaders[new URL(q.url).host] || {}); } catch (e) {}
        if (q.range) o.headers.Range = q.range;
        return nativeFetch(q.url, o);
      };
      // Same-origin cookies first (what most players do); a server that wants cookies across sites
      // must allow credentials, so try that if the first way fails.
      get('same-origin').catch(function () { return get('include'); }).then(function (r) {
        var len = +(r.headers.get('content-length') || 0);
        if (r.status === 200 && q.range && len > 64 * 1048576) {
          head({ status: 0, error: 'server ignores ranges on a large file' });
          try { r.body.cancel(); } catch (e) {}
          return;
        }
        return r.arrayBuffer().then(function (buf) {
          head({ status: r.status, range: r.headers.get('content-range') || '', type: r.headers.get('content-type') || '' });
          if (r.ok) P.postMessage(buf);
        });
      }).catch(function (e) { head({ status: 0, error: String((e && e.message) || e) }); });
    };
    P.postMessage('hi');
    return true;
  };
  if (!pipeReady()) document.addEventListener('DOMContentLoaded', pipeReady);

  // Playlists the page's player receives go to the app too, so the TV player can start from the
  // same copy: some servers tie a playlist to a session only the page has (partitioned cookies).
  try {
    var isList = function (u) { return /\.(m3u8|mpd)(\?|#|$)/i.test(u); };
    var isListType = function (t) { return /mpegurl|dash\+xml/i.test(t || ''); };
    var sendList = function (url, finalUrl, text) {
      if (typeof text !== 'string' || text.length > 2097152) return;
      if (!/^\s*#EXTM3U|<MPD/.test(text.slice(0, 2048))) return;
      if (!pipePost) return;
      try { pipePost({ t: 'pl', url: new URL(url, location.href).href, fin: finalUrl || '', body: text }); } catch (e) {}
    };
    var xOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__clearUrl = String(u);
      return xOpen.apply(this, arguments);
    };
    var xSet = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
      if (this.__clearUrl) noteHeader(this.__clearUrl, String(k), String(v));
      return xSet.apply(this, arguments);
    };
    var xSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      x.addEventListener('load', function () {
        try {
          if (x.status !== 200) return;
          if (x.responseType === 'arraybuffer') {
            // Custom loaders fetch playlists as bytes: look at the first ones, whatever the address.
            var ab = x.response;
            if (!ab || ab.byteLength > 2097152 || ab.byteLength < 7) return;
            if (String.fromCharCode.apply(null, new Uint8Array(ab, 0, 7)) !== '#EXTM3U') return;
            sendList(x.__clearUrl, x.responseURL, new TextDecoder().decode(ab));
            return;
          }
          if (x.responseType && x.responseType !== 'text') return;
          if (!isList(x.__clearUrl) && !isList(x.responseURL) && !isListType(x.getResponseHeader('content-type'))
              && String(x.responseText).slice(0, 7) !== '#EXTM3U') return;
          sendList(x.__clearUrl, x.responseURL, x.responseText);
        } catch (e) {}
      });
      return xSend.apply(this, arguments);
    };
    var realFetch = window.fetch;
    if (realFetch) window.fetch = function (input) {
      var p = realFetch.apply(this, arguments);
      try {
        var url = typeof input === 'string' ? input : (input && input.url) || String(input);
        var init = arguments[1];
        if (init && init.headers) new Headers(init.headers).forEach(function (v, k) { noteHeader(url, k, v); });
        p.then(function (r) {
          if (!r.ok || !(isList(url) || isList(r.url) || isListType(r.headers.get('content-type')))) return;
          r.clone().text().then(function (t) { sendList(url, r.url, t); }, function () {});
        }, function () {});
      } catch (e) {}
      return p;
    };
  } catch (e) {}

  // No autoplay: a video loads but plays only when the user presses play. Sites start hover
  // previews, hero trailers and the next episode by themselves; each one also decodes video and
  // holds GPU memory on a TV that has little. A video may start right after a press in its frame
  // (within a second), or up to 5 s after one when the press was on the video itself or inside an
  // embedded player (players often load the stream first). The remote's play key counts as a
  // press. Once the user started it, a video can resume freely; when it ends, the next one waits.
  var hold = false;
  try { hold = B.holdPreviews(); } catch (e) {}
  if (hold) {
    var pressAt = 0, pressX = -1, pressY = -1;
    var pressed = function (e) {
      if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
      pressAt = Date.now();
      var p = e.touches && e.touches[0] ? e.touches[0] : e;
      pressX = typeof p.clientX === 'number' ? p.clientX : -1;
      pressY = typeof p.clientY === 'number' ? p.clientY : -1;
      if (DEBUG) try { B.videoStats(JSON.stringify({ press: location.hostname, type: e.type, x: Math.round(pressX), y: Math.round(pressY) })); } catch (x) {}
    };
    ['pointerdown', 'mousedown', 'touchstart', 'keydown'].forEach(function (t) {
      window.addEventListener(t, pressed, true);
    });
    var byUser = function (v) {
      var since = Date.now() - pressAt;
      if (since < 1000) return true;
      if (since > 5000) return false;
      if (window.top !== window) return true;
      var r = v.getBoundingClientRect();
      return pressX >= r.left && pressX <= r.right && pressY >= r.top && pressY <= r.bottom;
    };
    var held = function (v) {
      if (DEBUG && v.tagName === 'VIDEO') try {
        B.videoStats(JSON.stringify({ playTry: location.hostname, muted: v.muted, vol: v.volume, ok: !!v.__clearOk,
          sincePress: pressAt ? Date.now() - pressAt : -1 }));
      } catch (e) {}
      if (v.tagName !== 'VIDEO' || v.__clearOk) return false;
      if (byUser(v)) { v.__clearOk = true; return false; }
      return true;
    };
    // Some players don't expect a refused play() and sit on their loading spinner with nothing to
    // press. So a held video with sound that fills a good part of its frame (a real player, not a
    // preview) gets our own Play button on top; pressing it plays the video, as its own button would.
    var playButton = function (v) {
      if (v.__clearBtn || v.muted || !document.documentElement) return;
      var r = v.getBoundingClientRect();
      if (r.width < 240 || r.height < 135 || r.width * r.height < innerWidth * innerHeight * 0.25) return;
      var b = document.createElement('div');
      b.setAttribute('role', 'button');
      b.setAttribute('aria-label', 'Play');
      var place = function () {
        var q = v.getBoundingClientRect();
        b.style.cssText = 'all:initial;position:fixed;z-index:2147483647;display:flex;align-items:center;justify-content:center;'
            + 'left:' + q.left + 'px;top:' + q.top + 'px;width:' + q.width + 'px;height:' + q.height + 'px;'
            + 'background:rgba(0,0,0,.35);cursor:pointer';
      };
      place();
      b.innerHTML = '<div style="all:initial;display:flex;align-items:center;gap:14px;padding:18px 34px 18px 28px;border-radius:999px;'
          + 'background:#e50914;color:#fff;font:600 26px/1 system-ui,sans-serif;box-shadow:0 6px 24px rgba(0,0,0,.5)">'
          + '<svg width="30" height="30" viewBox="0 0 24 24" fill="#fff"><path d="M8 5v14l11-7z"/></svg>Play</div>';
      var done = function () {
        if (!v.__clearBtn) return;
        v.__clearBtn = null;
        b.remove();
        removeEventListener('resize', place);
      };
      b.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation();
        done();
        v.__clearOk = true;
        realPlay.call(v).catch(function () {});
      }, true);
      v.addEventListener('playing', done);
      v.addEventListener('emptied', done);
      addEventListener('resize', place);
      v.__clearBtn = b;
      document.documentElement.appendChild(b);
    };
    var heldOne = function (v) {
      try { playButton(v); } catch (e) {}
      S0.previewsHeld++;
      if (DEBUG) try { B.videoStats(JSON.stringify({ autoplayHeld: String(v.currentSrc || v.src).slice(0, 120) })); } catch (e) {}
    };
    var realPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      if (held(this)) {
        heldOne(this);
        return Promise.reject(new DOMException('Autoplay held: press play', 'NotAllowedError'));
      }
      return realPlay.apply(this, arguments);
    };
    // The autoplay attribute starts playback without calling play().
    document.addEventListener('play', function (e) {
      if (held(e.target)) { heldOne(e.target); e.target.pause(); }
    }, true);
    document.addEventListener('ended', function (e) { e.target.__clearOk = false; }, true);
  }

  // Link prefetch: when the pointer rests on a link (or presses it), have the app fetch that
  // page's HTML into the cache, so the click lands on a warm response. Same site only.
  if (window.top === window && B.prefetch) {
    var hoverTimer = 0, lastLink = '';
    var linkOf = function (e) {
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (!a || a.target === '_blank' || !/^https:/.test(a.href)) return '';
      return a.href.split('#')[0] === location.href.split('#')[0] ? '' : a.href;
    };
    var go = function (h) { if (h && h !== lastLink) { lastLink = h; try { B.prefetch(h); } catch (x) {} } };
    document.addEventListener('mouseover', function (e) {
      var h = linkOf(e);
      clearTimeout(hoverTimer);
      if (h) hoverTimer = setTimeout(function () { go(h); }, 200);
    }, true);
    document.addEventListener('pointerdown', function (e) { go(linkOf(e)); }, true);
  }

  // Element hiding. Site rules arrive at once; generic rules only for the ids/classes the page
  // actually uses, the way uBlock Origin does it, so a TV CPU never parses 13k unused rules.
  var S = S0;
  var style = document.createElement('style');
  var seen = Object.create(null), pending = [], timer = 0;
  function attach() {
    var root = document.head || document.documentElement;
    if (root && style.parentNode !== root) root.appendChild(style);
  }
  function add(css) {
    if (!css) return;
    S.appends++; S.cssKB += css.length / 1024;
    style.appendChild(document.createTextNode(css));
    attach();
  }
  function note(t) { if (!seen[t]) { seen[t] = 1; pending.push(t); } }
  function collect(el) {
    if (el.id) note('#' + el.id);
    var c = el.classList;
    if (c) for (var i = 0; i < c.length; i++) note('.' + c[i]);
  }
  function collectTree(root) {
    if (root.nodeType !== 1) return;
    collect(root);
    var t0 = performance.now();
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) collect(all[i]);
    S.scanned += all.length + 1; S.scanMs += performance.now() - t0;
  }
  function flush() {
    timer = 0;
    if (!pending.length) return;
    var t = pending.join(' ');
    pending = [];
    var t0 = performance.now(), css = '';
    try { css = B.generic(t); } catch (e) {}
    S.bridgeMs += performance.now() - t0; S.bridgeCalls++;
    add(css);
  }

  var t1 = performance.now(), siteCss = '';
  try { siteCss = B.css(location.hostname); } catch (e) {}
  S.bridgeMs += performance.now() - t1; S.bridgeCalls++;
  add(siteCss);

  // Playback health of in-page video, for the app's ClearVideo log: resolution, stalls (waiting
  // after it had started), dropped frames, and how much is buffered ahead.
  var vStalls = 0, vStallMs = 0, vStallAt = 0, vStarted = false;
  document.addEventListener('playing', function (e) {
    if (e.target.tagName !== 'VIDEO') return;
    vStarted = true;
    if (vStallAt) { vStallMs += Date.now() - vStallAt; vStallAt = 0; }
  }, true);
  document.addEventListener('waiting', function (e) {
    if (e.target.tagName === 'VIDEO' && vStarted && !vStallAt) { vStalls++; vStallAt = Date.now(); }
  }, true);
  if (DEBUG) setInterval(function () {
    var vs = document.getElementsByTagName('video'), v = null;
    for (var i = 0; i < vs.length; i++) if (!vs[i].paused) { v = vs[i]; break; }
    if (!v || !B.videoStats) return;
    var q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : {};
    var ahead = 0;
    for (var j = 0; j < v.buffered.length; j++) if (v.buffered.start(j) <= v.currentTime + 0.5) ahead = v.buffered.end(j) - v.currentTime;
    try {
      B.videoStats(JSON.stringify({ pos: Math.round(v.currentTime), res: v.videoWidth + 'x' + v.videoHeight,
        stalls: vStalls, stallMs: vStallMs + (vStallAt ? Date.now() - vStallAt : 0), dropped: q.droppedVideoFrames,
        frames: q.totalVideoFrames, ahead: Math.round(ahead), mse: /^blob:/.test(v.currentSrc), fs: !!document.fullscreenElement,
        muted: v.muted, vol: v.volume, frame: location.hostname }));
    } catch (x) {}
  }, 10000);

  // The subtitle list the page's player knows about, from the common web players: plain <track>
  // elements, Vidstack (<media-player>.textTracks), Video.js and JW Player. Thumbnail and chapter
  // tracks are left out; each entry carries its format when the player says it.
  function subtitleList() {
    var out = [], seen = Object.create(null);
    function add(src, label, lang, kind, type) {
      if (!src || typeof src !== 'string') return;
      if (kind && !/^(subtitles|captions)$/i.test(kind)) return;
      try { src = new URL(src, location.href).href; } catch (e) { return; }
      if (!/^https?:/.test(src) || seen[src]) return;
      seen[src] = 1;
      out.push({ src: src, label: label || '', lang: lang || '', type: type || '' });
    }
    var ts = document.querySelectorAll('track[src]');
    for (var i = 0; i < ts.length; i++) add(ts[i].src, ts[i].label, ts[i].srclang, ts[i].kind || 'subtitles', '');
    var mp = document.querySelectorAll('media-player');
    for (var m = 0; m < mp.length; m++) {
      try {
        var tl = mp[m].textTracks, arr = tl ? (tl.toArray ? tl.toArray() : Array.prototype.slice.call(tl)) : [];
        arr.forEach(function (t) { add(t.src, t.label, t.language, t.kind, t.type); });
      } catch (e) {}
    }
    try {
      if (window.videojs && videojs.getPlayers) {
        var ps = videojs.getPlayers();
        for (var id in ps) {
          var rt = ps[id] && ps[id].remoteTextTracks ? ps[id].remoteTextTracks() : null;
          for (var r = 0; rt && r < rt.length; r++) add(rt[r].src, rt[r].label, rt[r].language, rt[r].kind, '');
        }
      }
    } catch (e) {}
    try {
      var j = window.jwplayer && jwplayer();
      var item = j && j.getPlaylistItem && j.getPlaylistItem();
      ((item && item.tracks) || []).forEach(function (t) { add(t.file, t.label, t.language, t.kind || 'captions', ''); });
    } catch (e) {}
    return out;
  }

  // Tell the app where the page's video is, so the TV player can pick up at the same spot.
  var lastTime = 0;
  document.addEventListener('playing', function (e) {
    var v = e.target;
    if (v.tagName !== 'VIDEO') return;
    try { B.video('playing', v.currentTime || 0); } catch (x) {}
    // A real video (sound on, longer than a clip, or live) started: the app may move it to the TV player.
    var d = v.duration;
    if (!v.muted && v.volume > 0 && (!isFinite(d) || d > 90)) {
      // Every subtitle the page's player offers (it may not have downloaded them yet).
      var subs = subtitleList();
      if (pipePost) pipePost({ t: 'start', pos: v.currentTime || 0, subs: JSON.stringify(subs), src: v.currentSrc || '' });
    }
  }, true);
  document.addEventListener('timeupdate', function (e) {
    var now = Date.now();
    if (e.target.tagName !== 'VIDEO' || now - lastTime < 2000) return;
    lastTime = now;
    try { B.video('time', e.target.currentTime || 0); } catch (x) {}
  }, true);
  var obs = new MutationObserver(function (muts) {
    for (var i = 0; i < muts.length; i++) {
      var n = muts[i].addedNodes;
      for (var j = 0; j < n.length; j++) collectTree(n[j]);
    }
    if (style.childNodes.length) attach(); // pages that wipe <head> get the style back
    if (pending.length && !timer) timer = setTimeout(flush, 120);
  });
  function start() {
    if (!document.documentElement) return setTimeout(start, 10);
    collectTree(document.documentElement);
    flush();
    attach();
    obs.observe(document.documentElement, { childList: true, subtree: true });
  }
  start();
})();
