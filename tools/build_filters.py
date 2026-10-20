#!/usr/bin/env python3
"""Download ad/tracker/pop-up filter lists and compile them into the app's assets.

Outputs (app/src/main/assets/filters/):
    domains.txt          one blocked domain per line (subdomains are blocked too)
    generic.tsv          token <TAB> selector<US>selector...   element hiding for every site, keyed by the
                         selector's leading #id / .class (the app injects only tokens present on the page,
                         like uBlock Origin's generic cosmetics); key "*" holds the always-on ones

All three are sorted by key, byte order, because the app memory-maps them and binary-searches the
lines in place instead of loading them into its heap.
    cosmetic.tsv         domain <TAB> selector<US>selector...   site-specific element hiding

Only the parts of the adblock syntax a WebView can honour are kept: plain `||domain^` network
rules (with harmless type options) and plain CSS `##` hiding rules. Rules scoped with
`domain=`, exceptions with options, scriptlets and uBO procedural selectors are dropped.

Run:  python3 tools/build_filters.py
"""
import os
import re
import sys
import urllib.request

LISTS = [
    ("EasyList", "https://easylist.to/easylist/easylist.txt", "abp"),
    ("EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "abp-network"),
    ("uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt", "abp"),
    ("uBlock badware", "https://ublockorigin.github.io/uAssets/filters/badware.txt", "abp"),
    ("uBlock privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt", "abp-network"),
    ("AdGuard Base", "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt", "abp"),
    ("AdGuard Popups", "https://filters.adtidy.org/extension/ublock/filters/19.txt", "abp"),
    ("Peter Lowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", "hosts"),
]

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "filters")

# Network options that don't narrow *where* a rule applies, so a domain-wide block is still right.
OK_OPTS = {
    "third-party", "3p", "first-party", "1p", "~third-party", "popup", "popunder", "script", "image", "xhr",
    "xmlhttprequest", "subdocument", "frame", "all", "doc", "document", "important", "media", "object",
    "ping", "websocket", "other", "stylesheet", "css", "font", "match-case", "~image", "~script",
    "~stylesheet", "~xmlhttprequest", "~subdocument", "~media", "~font", "~document",
}
DOMAIN_RE = re.compile(r"^\|\|([a-z0-9.-]+\.[a-z0-9-]{2,})\^(?:\$(.*))?$")
# uBO / AdGuard extended selectors a normal CSS engine rejects or that need script support.
PROCEDURAL = re.compile(
    r":-abp-|:has-text|:upward|:xpath|:matches-css|:min-text-length|:watch-attr|:remove|:style\(|"
    r":others|:matches-path|:matches-attr|:matches-prop|:matches-media|:if\(|:if-not|:nth-ancestor|"
    r":spath|:contains|:properties|\{|\}|^\^|^\+js")
# Generic hiding goes on every page of every site, so only accept selectors that are plainly valid.
TOKEN = re.compile(r"^[#.][A-Za-z0-9_-]+")
SAFE_GENERIC = re.compile(r"^[#.\[a-zA-Z][\w\-#.\[\]=\"'^$*~|:>+ ()]*$")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 clearbrowser-filter-build"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def main():
    blocked, unblocked = set(), set()
    generic = set()
    cosmetic = {}  # domain -> set(selectors)
    for name, url, kind in LISTS:
        try:
            text = fetch(url)
        except Exception as e:  # one dead mirror shouldn't kill the build
            print(f"  ! {name}: {e}", file=sys.stderr)
            continue
        before = len(blocked)
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith("!") or line.startswith("["):
                continue
            if kind == "hosts":
                parts = line.split()
                if len(parts) >= 2 and parts[0] in ("0.0.0.0", "127.0.0.1") and "." in parts[1]:
                    blocked.add(parts[1].lower())
                continue
            if "##" in line and kind == "abp":
                dom, _, sel = line.partition("##")
                sel = sel.strip()
                if not sel or PROCEDURAL.search(sel) or "#@#" in line or "#?#" in line or "#$#" in line:
                    continue
                if not dom:
                    if SAFE_GENERIC.match(sel):
                        generic.add(sel)
                    continue
                for d in dom.split(","):
                    d = d.strip().lower()
                    if d and not d.startswith("~") and "*" not in d and "/" not in d:
                        cosmetic.setdefault(d, set()).add(sel)
                continue
            if line.startswith("#"):
                continue  # other cosmetic flavours (#@#, #?#, #$#, #%#)
            exc = line.startswith("@@")
            m = DOMAIN_RE.match(line[2:] if exc else line)
            if not m:
                continue
            opts = [o for o in (m.group(2) or "").split(",") if o]
            if any(o not in OK_OPTS for o in opts):
                continue
            (unblocked if exc else blocked).add(m.group(1))
        print(f"  {name}: +{len(blocked) - before} domains")

    blocked -= unblocked
    blocked = {d.strip(".") for d in blocked if "." in d}
    # A parent already covers its subdomains, so drop the redundant children (smaller file, less RAM).
    def covered(d):
        p = d.split(".")
        return any(".".join(p[i:]) in blocked for i in range(1, len(p) - 1))
    blocked = sorted(d for d in blocked if not covered(d))

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "domains.txt"), "w") as f:
        f.write("\n".join(sorted(blocked, key=lambda d: d.encode())) + "\n")
    by_token = {}
    for sel in generic:
        m = TOKEN.match(sel)
        by_token.setdefault(m.group(0) if m else "*", []).append(sel)
    with open(os.path.join(OUT, "generic.tsv"), "w") as f:
        for tok in sorted(by_token, key=lambda k: k.encode()):
            f.write(tok + "\t" + "\x1f".join(sorted(by_token[tok])) + "\n")
    with open(os.path.join(OUT, "cosmetic.tsv"), "w") as f:
        for d in sorted(cosmetic, key=lambda k: k.encode()):
            f.write(d + "\t" + "\x1f".join(sorted(cosmetic[d])) + "\n")
    print(f"domains={len(blocked)} generic={len(generic)} cosmetic_sites={len(cosmetic)} -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
