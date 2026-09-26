# manonga.online — official script canary v1 (audit + publish record)

## Site profile (audited 2026-09-26, live fetch, curl UA = app UA)

- Small, server-rendered Arabic manga site. No Cloudflare (plain 200s on
  `/`, `/manga-list`, detail, chapter, search), no auth, HTTPS everywhere.
- Custom theme (jQuery/bootstrap-era markup): fixed engine walks cannot
  express its routes, so a script is genuinely required — not just convenient.
- Routes: `/` (home, `.manga-item` x54), `/manga-list?page=N[&cat=<id>]`
  (`.col-sm-4` cards, pager `?page=N`), `/manga/<slug>` (title tag, h3 meta,
  `img.img-responsive` cover, `ul.chapters li h5 a`), `/manga/<slug>/<num>`
  (bare lazy imgs, `data-src` real + base64 `src` placeholder),
  `/search?query=` → `{"suggestions":[{"value","data"}]}`.
- Hosts: `manonga.online` (pages, some covers), `www.onma.top` (covers,
  chapter images, downloads). Literal allow-list — 2 hosts, no wildcards.
- Genre taxonomy `?cat=<id>` scraped live (34 entries, embedded in source.js).
  Note: the taxonomy includes adult-leaning categories (like most general
  aggregators); the canary ships `enabledByDefault=false`, dogfood-only
  announcement. The app's parental controls + blacklist apply as usual.

## Rejected alternatives

- manhwaarab.com (Supabase SPA shell — genuine API case but auth/RLS surface
  too risky for a first canary), arabymanga.com (`/mangas` 404s — unstable
  surface), kawaiimanga.org (Next.js/Turbopack shell — RSC reverse-engineering
  risk), mangatey.com (Madara WP — a descriptor could serve it; less script
  justification), adult verticals (excluded outright).

## Verification performed (this staging)

- Every selector executed against the captured fixtures with BeautifulSoup
  (same selector strings, same documents — see transcript in the publish log).
- `harness.mjs`: REAL entry functions in Node with stubbed bridge values —
  control flow, key names, JSON shapes all pass (`node harness.mjs`).
- `source.js` passes `node --check`; contains no `*/`-in-comment hazard.
- Fixtures: `fixtures/` (full captured pages, 2026-09-26).

## Sign-off checklist (human publisher)

- [ ] Re-verify selectors still match live (sites drift; recapture if stale)
- [ ] `scriptSha256` = lowercase hex sha256 of the EXACT `source.js` bytes
- [ ] JCS-canonicalize manifest minus `signature`; sign with `official-1`
      (offline private key); verify with the public pin before promoting
- [ ] Promote `plugin.json` + `source.js` to `public/plugins/manonga/v1/`
- [ ] Bump `public/plugins/index.json` (`version` + `updatedAt`)
- [ ] Dogfood announcement (NOT release notes); watch fleet telemetry:
      `plugin_sync` (Updated for manonga), `script_call` samples, no
      unintended quarantines
- [ ] v2 update drill + rollback drill per `tmp/stabilization-plan-v9.md`
