# `_archive/` — previous signed versions (NOT served, NOT synced)

One-click rollback stock: `_archive/<id>/v<version>/plugin.json` (+ `source.js`
for script kinds) holds every superseded SIGNED version. Rollback = promote an
archived version back into `public/plugins/` + bump `index.json`, exactly like a
normal publish (same review gate, same signature verification on-device).

Never delete an archived version that a shipped app may still have active —
the app's rollback pointer can only restore versions whose bytes still exist
in distribution. Eviction policy: keep all versions referenced by any published
release's supported range; prune only with a reviewer note.
