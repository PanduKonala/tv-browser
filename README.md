# Clear Browser

A small, single-tab, ad-blocking web browser for Android TV, built on the system WebView so it
stays light on 1 GB TVs. Remote-first: the D-pad drives a mouse pointer. Opens on its own home
page (search, bookmarks, recently visited; instant, works offline); searches go to Google; the
Bookmarks button in the toolbar jumps to the bookmarks.

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
- **Site notices**: the app's own list (`assets/site_fixes.txt`, uBlock Origin's
  `set-local-storage-item` syntax) stores a site's "dismissed" flag before the page loads, so a
  nag the site shows until you close it never appears (for example tbcpl.lol's "use Brave or
  uBlock Origin" notice). Block-list updates don't touch it.
- The shield in the toolbar shows the count for the page and turns blocking off for a site that breaks.

## Streaming

- **TV player, automatic**: when a page starts a real video (sound on, longer than 90 s or
  live) and the browser has seen its stream, playback moves to the TV player by itself; muted
  previews, short clips and YouTube stay in the page. Besides smoothness, this is how Dolby
  audio plays: the in-page player can't decode E-AC-3/AC-3 (common in .mkv files) and stays
  silent, while the TV player hands it to the TV's decoder. Measured on a 27-min 1080p .mkv:
  in-page 16 stalls in 21 s, 10 % dropped frames, no sound; TV player 0 stalls, 0 dropped, E-AC-3 sound.
  While the TV player is open, a page player that restarts itself behind it is paused again
  (no second TV player).
- **Session-bound playlists**: the page's player passes each playlist it receives to the app, and
  the TV player starts from that copy. Some embeds (enma.lol's tryembed server) keep the stream
  session in partitioned cookies, which Android's CookieManager doesn't hand out, so asking the
  server again gets "403 Missing stream session". Only master and finished playlists are reused;
  live ones are fetched fresh.
- **TV player, on fullscreen**: when a site's player goes fullscreen, the stream it was playing (HLS `.m3u8`,
  DASH `.mpd`, or a video file) opens in a built-in ExoPlayer with the page's headers, cookies
  and position. Video goes straight to the TV's hardware video plane instead of being composited
  through WebView. Measured on the Bedroom TV, 1080p over 2 min: web player 4-9 stalls and 6-12 %
  dropped frames; TV player 0 stalls, 0 dropped. Back returns to the page at the same spot; if a
  stream won't play natively, the page's own player takes over. Toggle in ⋮.
- **Subtitles** (TV player): tracks inside the video plus subtitle files the page used (scrubbing
  thumbnail .vtt files are ignored; a subtitle file that fails is dropped without stopping playback).
  **Menu**, a held **OK**, or the Captions key opens the panel: on/off, track, size (small to extra
  large), style (white/yellow, outlined or on a dark box), position (bottom/raised). Choices carry over.
- **Parallel downloads** (TV player): each segment is fetched as byte ranges over up to 32
  connections. Per host it starts at 8 and doubles after clean downloads; a failed piece is retried
  and halves the count (down to 1); servers without range support get one plain stream (remembered per server, so video segments don't each pay an extra request); at most
  16 MB is in flight. A refused piece (403/416/429/503, i.e. rate limiting) switches the rest of
  the download to one continuous connection from that byte, and the host stays single-stream.
- **Seeking** (TV player, controls hidden): a Left/Right tap moves exactly 1 s; holding speeds up
  (2 s, 5 s, 10 s, 30 s steps); FF/Rewind jump 10 s. Playback pauses while you scrub and jumps 1 s
  after the last key (or on OK); Back cancels. A carousel shows previews around the target time
  (the site's WebVTT thumbnail sprites when its player has them, fetched with the player's Origin;
  otherwise frames from the video), with the time, the offset and a progress bar.
- **Tunneled playback** (TV player): the TV's video hardware does frame timing and A/V sync
  (these TCL/MediaTek decoders support it for H.264/HEVC/VP9/AV1). Measured on a 1080p .mkv:
  ~31 % total CPU vs ~48 % without, 0 dropped frames. Off switch in the player's Menu panel.
- **Quality**: capped at 1080p / 60 fps (both players); below that, whatever the site serves.
- **RAM**: filter lists are memory-mapped from the APK (no heap); in-page hls.js players get a lean
  buffer (10 s behind, 20-40 s ahead) unless the site sets its own; Wi-Fi is kept out of power-save
  while the browser is open.

## Page speed and RAM

- **Bigger disk cache**: WebView's HTTP cache raised from 20 MB toward 1 GB, always leaving 300 MB
  of storage free (WebView itself caps it at 320 MB on these TVs). Repeat visits read pages, images
  and compiled scripts from storage, not RAM or network.
- **Back/forward cache**: Back shows the previous page from memory instead of reloading it
  (1 page, 10 minutes, dropped by Chromium under memory pressure).
- **Link prefetch**: when the pointer rests on a same-site link for 200 ms (or presses it), that
  page's HTML is fetched into the cache. Pages' own speculation rules are enabled too.
- **Learned preconnect** (Chrome's loading predictor): the servers each site uses are remembered,
  and connections to them (DNS + TLS) open in parallel as soon as you navigate there. On these TVs
  DNS-over-TLS lookups took up to 1.6 s each; with preconnect they mostly drop to 0.
- **Renderer warm-up** at launch.
- **No hover previews or muted trailers** (toggle in ⋮): a muted video plays only right after a
  click (you pressed its play button), so hovering over a card never starts one. They decode video
  and hold GPU memory while you're only browsing. Videos with sound play normally.
- Blocker cost per page is kept off the page's main thread: hiding rules indexed in the heap,
  list answers cached per host, list files pre-warmed at start.
- With test logging on, `adb logcat -s ClearPerf` prints load timing and blocker cost for every page.

Measured on the Bedroom TV, cold app start, first paint: YouTube 5.7-7.3 s -> 3.4 s,
Crunchyroll 5.9 s -> 3.3 s (fully loaded 9.7 s -> 2.8 s).

TCL "Memory Shrinker" (in the firmware's ActivityManager): a process that isn't the on-screen app
is killed once it passes ~102 MB if it's backgrounded (adj > 700), or 200 MB at any priority
below foreground. WebView's page renderer is a separate process, so leaving the browser with a
heavy page open gets that page killed; it reloads when you come back. While the browser is on
screen the renderer runs at foreground priority and is safe.

Known limit: both TVs' low-memory killer (lmkd) fails to read /proc/zoneinfo ("parse error"), so
idle apps are never reclaimed and the system lives in swap. That's TCL firmware; it needs root or a
firmware update to fix.

## Block-list updates

Every 4 days (the lists' own "Expires", clamped to 1-14 days) the app downloads the lists over
HTTPS and compiles them on the TV (`FilterCompiler`, a byte-identical port of
`tools/build_filters.py`; ~33 s on these TVs), sanity-checks the result, then swaps the new files
in at once. Any failure keeps the current lists and retries in an hour. Never runs while the TV
player is on screen. ⋮ → "Update ad-block lists now" forces it.

## Test logging

Off by default. `adb shell setprop log.tag.Clear DEBUG` and restart the app to get per-page timing
and blocker cost (`ClearPerf`), per-request blocks (`ClearBlock`), in-page video stats
(`ClearVideo`), TV player stats and tunneling state (`ClearPlayer`) and downloader decisions (`ClearDL`).

## Smooth scrolling

Pushing the pointer into the top or bottom edge drags the page with a virtual finger, so Chromium
scrolls it on its compositor thread (smooth even while the site's scripts are busy) and scrolls
whatever box is under the pointer; letting go stops without a fling. Hover events pause while
scrolling. Frosted-glass blur (`backdrop-filter`) is switched off on pages because these GPUs
recompute it every frame (⋮ → "Smooth scrolling" to keep it). Measured on 1shows.org, 12 s of
held scrolling: before ~4 fps (47 frames, all late); after ~60 fps (773 frames, median 12 ms, 18 late).

## Remote

| Key | Action |
| --- | --- |
| Arrows | move the pointer (hold to speed up); push into an edge to scroll |
| OK | click |
| Hold OK / Menu | toolbar (back, forward, reload, home, address, bookmark, shield, ⋮) |
| Back | leave fullscreen → go back → toolbar |
| Play/Pause, FF/Rew | control the page's video (top-level players) |
| Channel ± | smooth page up / down |

⋮ has: set this page as home / Google as home, desktop/mobile site, ad blocking per site, TV
player on/off, play this page's video in the TV player, forget site choices, clear cookies.

## Build & install

    python3 tools/build_filters.py           # refresh filter lists (optional)
    ./install.sh 192.168.88.8 192.168.88.6   # build + sideload over ADB (uses ../cleanyourtv/tools/tv.py)
