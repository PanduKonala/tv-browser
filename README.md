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

## Translation

- A page that isn't in English (by its declared language, or mostly non-Latin text when it doesn't
  declare one) shows a note and a translate button in the toolbar, right after the address
  (Menu, then →). ⋮ → "Translate this page to English" does the same.
- Pages open through Google Translate's page proxy (`site-com.translate.goog/...`). Once a site is
  translated, its other pages open translated too, including links its own scripts navigate to,
  until "Show the original page" (the same button, or ⋮).
- The proxied page runs on Google's address: logins and some video players don't carry over, so
  switch back to the original for those. Google's embeddable translate widget was retired on
  2026-10-01, so it isn't used.

## Streaming

- **TV player, automatic**: when a page starts a real video (sound on, longer than 90 s or
  live) and the browser has seen its stream, playback moves to the TV player by itself; muted
  previews, short clips and YouTube stay in the page. Besides smoothness, this is how Dolby
  audio plays: the in-page player can't decode E-AC-3/AC-3 (common in .mkv files) and stays
  silent, while the TV player hands it to the TV's decoder. Measured on a 27-min 1080p .mkv:
  in-page 16 stalls in 21 s, 10 % dropped frames, no sound; TV player 0 stalls, 0 dropped, E-AC-3 sound.
  While the TV player is open, a page player that restarts itself behind it is paused again
  (no second TV player).
- **Any player, any address**: the page's player passes each playlist it receives to the app, so a
  stream is found by its content even when its address doesn't look like one (`/proxy?url=…`, no
  extension); the TV player takes the newest quality list from the frame whose video started, with
  the Origin/Referer that frame's player sends.
- **Through the page when refused**: when a server refuses the TV player (401/403), the frame that
  plays the video downloads the bytes itself (its cookies, partitioned ones included, its Origin and
  Referer, and the extra headers its own scripts add, such as session tokens) and passes them back as
  ArrayBuffers over an androidx.webkit message channel; large files come in 4 MB ranged pieces. That
  server stays on this route for the session. While the TV player is open the page keeps running
  (no paused timers) and its renderer keeps an important priority, so the TV's Memory Shrinker
  doesn't kill it. Tested against a server that refuses anything without the page's session header:
  60 s at 720p, 0 stalls.
- **Best quality the page offers**: tube sites' players often start on a reduced list (xvideos
  loads `hls_low.m3u8`, 480p and 360p, while `hls.m3u8` beside it has 1080p). Before playing, the
  TV player also looks at the playlists the page mentions (on the stream's server) and the same
  name without a "low" marker, fetches them (small, at most 3 s each) and starts on the one with the
  highest quality; the 1080p/60 cap still applies. Anything slow or refused just doesn't count.
- **Remote play key**: with nothing playing, it starts the page's main video (the largest on
  screen), never a small muted hover preview.
- **Session-bound playlists**: the TV player starts from the page's copy of the playlist, and learns
  its type (HLS/DASH) from the content when the address doesn't say. Some embeds (enma.lol's tryembed server) keep the stream
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
- **No autoplay** (toggle in ⋮): a video loads but plays only when you press play. Hover previews,
  hero trailers and the next episode wait; with sound or muted. A video may start within a second of
  a press in its frame, or up to 5 s after a press on the video itself or inside an embedded player
  (players often load the stream first); the remote's play key counts as a press. A held video with
  sound that fills its player gets a red **▶ Play** button on top, since some players don't expect
  to be held and would sit on their loading spinner. Each preview would
  also decode video and hold GPU memory on a TV that has little.
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

## Security

Every page, and every ad frame inside it, is treated as hostile. What a page can and can't make
the browser do:

- **Engine updates**: pages run in the system WebView (Chromium, sandboxed renderer). Its security
  fixes come through the Play Store; keep that enabled (checked 2026-10-21: WebView 155 on both TVs).
- **No reach outside the web**: no `intent://`/`market://` app launches, no downloads, no camera,
  microphone or location, no file or content access (`file:` only for our own home page), no
  WebView remote debugging. Certificate errors cancel the load (there is no "proceed anyway").
- **Other apps** can open http/https addresses in the browser and nothing else: a `javascript:`
  or `file:` address sent to it is ignored. The TV player isn't exported.
- **Home network**: the TV player, its subtitles and thumbnails download natively, outside the
  browser's Private Network Access guard. They use one HTTP client (SafeHttp, OkHttp) that, unless
  the page itself is on the local network, refuses names resolving to local addresses (router, NAS,
  the TV) and checks the address every connection actually reached, redirects and numeric
  addresses included, so DNS rebinding can't slip through.
- **The JavaScript bridge** (every frame has it) only offers harmless calls: hiding rules for a
  host, link prefetch within the site being shown, video events. Home-page actions (remove
  bookmark, clear recents) work only while our home page is shown, and it renders site titles as
  text and makes only http/https links.
- **Frame reports** (a playlist arrived, a video started) come over the web-message channel, where
  WebView names the sending frame; the JS bridge can't tell frames apart, so nothing frame-specific
  goes through it. A frame can't take over another frame's stream entry, playlist copies belong to
  the frame that received them, and the TV player reads only the playing frame's copies; all copies
  stay under 8 MB. Known limit: any frame can claim "my video started", so an ad frame on the page
  can race the real video into the TV player with its own stream (an annoyance, not a way in; the
  home-network guard still applies).
- **The page pipe** asks one frame to fetch and accepts the answer only from that frame.
- **The debug test hook** (TEST_HOLD, only with test logging on) accepts broadcasts from adb only
  (it requires the DUMP permission, which apps can't hold).

Tested on the Living Room TV (2026-10-21) with a hostile test site (a public-looking nip.io name
pointing at a test server on the LAN):

| Attack | Result |
| --- | --- |
| App sends `javascript:` / `file:` addresses | ignored |
| Public page plays a stream at a LAN IP | TV player never fetched it |
| Public page plays a stream at a name resolving to the LAN (rebinding) | refused at lookup, back to the page; 0 native requests |
| Second frame spams fake pipe answers (200/s) and claims the player's playlist | 0 requests for its stream, 0 playback errors |
| 40 × 2 MB fake playlists | Java heap +8.9 MB after GC (the 8 MB cap) |
| Page opens `file:` home page (top and iframe), calls clear-recents / remove-bookmark | blocked; bookmarks and recents intact |
| Location, `file:` fetch, APK download, `intent://`, `market://` | denied / blocked / nothing saved / no app opened |
| Camera, microphone | the TV has none (NotFoundError); requests are denied in code |
| Debug hook | Android reports requiredPermission=DUMP |

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
