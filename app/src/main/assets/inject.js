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
      else if (cmd === 'toggle') { if (v.paused) v.play(); else v.pause(); }
      else if (cmd === 'play') v.play();
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

  // Lighter rendering for TV GPUs: frosted-glass blur (backdrop-filter) is recomputed for every
  // scroll frame and is the biggest single cost on these chips. Without it, translucent bars stay
  // translucent, just not blurred.
  var lite = true;
  try { lite = B.liteEffects(); } catch (e) {}
  if (lite) {
    var ls = document.createElement('style');
    ls.textContent = '*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}';
    (document.head || document.documentElement).appendChild(ls);
    document.addEventListener('DOMContentLoaded', function () { if (!ls.isConnected) (document.head || document.documentElement).appendChild(ls); });
  }

  // Muted autoplay is how sites run previews and hero trailers while you're only browsing; each
  // one decodes video and holds GPU memory (about 16 MB on one Netflix-style page here). Hold
  // those back until the first click on the page. Videos with sound, and anything after a click,
  // play as normal.
  var hold = false;
  try { hold = B.holdPreviews(); } catch (e) {}
  if (hold && navigator.userActivation) {
    var held = function (v) { return v.muted && !navigator.userActivation.hasBeenActive; };
    var realPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      if (this.tagName === 'VIDEO' && held(this)) {
        S0.previewsHeld++;
        return Promise.reject(new DOMException('Muted autoplay held until a click', 'NotAllowedError'));
      }
      return realPlay.apply(this, arguments);
    };
    // The autoplay attribute starts playback without calling play().
    document.addEventListener('play', function (e) {
      var v = e.target;
      if (v.tagName === 'VIDEO' && held(v)) { S0.previewsHeld++; v.pause(); }
    }, true);
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
        frames: q.totalVideoFrames, ahead: Math.round(ahead), mse: /^blob:/.test(v.currentSrc), fs: !!document.fullscreenElement }));
    } catch (x) {}
  }, 10000);

  // Tell the app where the page's video is, so the TV player can pick up at the same spot.
  var lastTime = 0;
  document.addEventListener('playing', function (e) {
    var v = e.target;
    if (v.tagName !== 'VIDEO') return;
    try { B.video('playing', v.currentTime || 0); } catch (x) {}
    // A real video (sound on, longer than a clip, or live) started: the app may move it to the TV player.
    var d = v.duration;
    if (!v.muted && v.volume > 0 && (!isFinite(d) || d > 90)) {
      // Subtitle tracks the player declared (it may not have downloaded them yet).
      var subs = [];
      var ts = v.querySelectorAll('track[src]');
      for (var i = 0; i < ts.length; i++) {
        var k = ts[i].kind || 'subtitles';
        if (k === 'subtitles' || k === 'captions') subs.push({ src: ts[i].src, label: ts[i].label || '', lang: ts[i].srclang || '' });
      }
      try { B.videoStarted(v.currentTime || 0, JSON.stringify(subs), v.currentSrc || ''); } catch (x) {}
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
