# Clear Browser

A small, single-tab, ad-blocking web browser for Android TV, built on the system WebView so it
stays light on 1 GB TVs. Remote-first: the D-pad drives a mouse pointer. Opens Google; searches
go to Google; the Bookmarks button in the toolbar lists saved pages.

## Blocking

- **Network**: ~110k ad, tracker, malware and pop-under domains compiled from EasyList,
  EasyPrivacy, uBlock filters / badware / privacy, AdGuard Base, AdGuard Popups and Peter Lowe's
  list. Requests to them (scripts, iframes, images, XHR) never leave the TV.
- **Element hiding**: site-specific `##` rules from those lists, plus generic rules applied only
  when the page actually uses the matching id/class (the way uBlock Origin does it).
- **Pop-ups**: windows a script opens without a click are dropped. Windows opened by a click go to
  ad domains → dropped; same site → open in place; other sites → asks (Open / Always / Block / Always block).
- **Redirects**: a page that sends you to another site without a click is stopped; so are
  `intent://` / `market://` app-store hijacks and downloads.
- The shield in the toolbar shows the count for the page and turns blocking off for a site that breaks.

## Streaming

- **TV player**: when a site's player goes fullscreen, the stream it was playing (HLS `.m3u8`,
  DASH `.mpd`, or a video file) opens in a built-in ExoPlayer with the page's headers, cookies
  and position. Video goes straight to the TV's hardware video plane instead of being composited
  through WebView. Measured on the Bedroom TV, 1080p over 2 min: web player 4-9 stalls and 6-12 %
  dropped frames; TV player 0 stalls, 0 dropped. Back returns to the page at the same spot; if a
  stream won't play natively, the page's own player takes over. Toggle in ⋮.
- **Parallel downloads** (TV player): each segment is fetched as byte ranges over up to 32
  connections. Per host it starts at 8 and doubles after clean downloads; a failed piece is retried
  and halves the count (down to 1); servers without range support get one plain stream; at most
  16 MB is in flight.
- **Quality**: capped at 1080p / 60 fps (both players); below that, whatever the site serves.
- **RAM**: filter lists are memory-mapped from the APK (no heap); in-page hls.js players get a lean
  buffer (10 s behind, 20-40 s ahead) unless the site sets its own; Wi-Fi is kept out of power-save
  while the browser is open.

## Page speed and RAM

- **Bigger disk cache**: WebView's HTTP cache raised from 20 MB to 256 MB (at most a third of free
  storage), so repeat visits read pages, images and compiled scripts from storage, not RAM or network.
- **Learned preconnect** (Chrome's loading predictor): the servers each site uses are remembered,
  and connections to them (DNS + TLS) open in parallel as soon as you navigate there. On these TVs
  DNS-over-TLS lookups took up to 1.6 s each; with preconnect they mostly drop to 0.
- **Renderer warm-up** at launch.
- **Muted previews and trailers wait for a click** (toggle in ⋮): they decode video and hold GPU
  memory while you're only browsing. Videos with sound, and anything after a click, play normally.
- Blocker cost per page is kept off the page's main thread: hiding rules indexed in the heap,
  list answers cached per host, list files pre-warmed at start.
- `adb logcat -s ClearPerf` prints load timing and blocker cost for every page.

Measured on the Bedroom TV, cold app start, first paint: YouTube 5.7-7.3 s -> 3.4 s,
Crunchyroll 5.9 s -> 3.3 s (fully loaded 9.7 s -> 2.8 s).

Known limit: both TVs' low-memory killer (lmkd) fails to read /proc/zoneinfo ("parse error"), so
idle apps are never reclaimed and the system lives in swap. That's TCL firmware; it needs root or a
firmware update to fix.

## Remote

| Key | Action |
| --- | --- |
| Arrows | move the pointer (hold to speed up); push into an edge to scroll |
| OK | click |
| Hold OK / Menu | toolbar (back, forward, reload, home, address, bookmark, shield, ⋮) |
| Back | leave fullscreen → go back → toolbar |
| Play/Pause, FF/Rew | control the page's video (top-level players) |
| Channel ± | page up / down |

⋮ has: set this page as home / Google as home, desktop/mobile site, ad blocking per site, TV
player on/off, play this page's video in the TV player, forget site choices, clear cookies.

## Build & install

    python3 tools/build_filters.py           # refresh filter lists (optional)
    ./install.sh 192.168.88.8 192.168.88.6   # build + sideload over ADB (uses ../cleanyourtv/tools/tv.py)
