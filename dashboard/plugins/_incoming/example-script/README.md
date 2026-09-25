# `example-script/` — script-kind TEMPLATE (not a real source)

This folder is the reference implementation for `engine: "script"` plugins. It is
**never promoted as-is**: `example.com` is not a manga site. Copy it as the starting
point for a real script source, then follow the publish flow below (same human
sign-off gate as descriptors).

Files:

- `plugin.json` — unsigned template. `scriptSha256` and `signature` are placeholders
  completed at promote time (never commit the private key; it lives in Vercel
  `PLUGIN_SIGNING_PRIVATE_KEY_B64` + the gitignored `tmp/secrets/` backup only).
- `source.js` — bridge-API-v1 implementation of the five required entries
  (`home`, `detail`, `pages`, `search`, `browse`) plus the optional `genres`.
  The logic mirrors the app's JVM fixture harness (`ScriptRunnerTest`), which is
  the executable spec: if your script passes an equivalent harness run, the
  app-side gates will accept its shape.

## Publish checklist (script kind)

1. **Stage**: copy this folder to `_incoming/<id>/`, retarget `baseUrl` +
   `allowedHosts` (literal hosts only, no wildcards in v1) + `names`, implement
   entries against captured fixtures.
2. **CI validates**: schema + semantics, Ed25519 signature against the pinned
   `official-1` key, `source.js` byte cap (512 KB), fixture smoke through the
   real Rhino sandbox on JVM (malicious-fixture suite: oversized / timeout /
   off-allowlist / redirect-escape / reflection attempts all contained).
3. **Hash**: `sha256sum source.js` (lowercase hex) → `scriptSha256` in
   `plugin.json`. The app re-checks this at download AND at install AND at
   boot-resume — a mismatch anywhere fails closed.
4. **Human reviewer approves** (CI passing is necessary but NOT sufficient).
5. **Sign + verify**: canonicalize (JCS, `signature` excluded) → sign with the
   offline private key → independently verify with the public pin
   (`dashboard/lib/plugin-keys.ts`) before promoting.
6. **Promote**: copy the signed version into
   `dashboard/public/plugins/<id>/v<version>/` (`plugin.json` + `source.js`),
   move the superseded version to `dashboard/plugins/_archive/<id>/`, bump
   `public/plugins/index.json` (`version` + `updatedAt`).
7. **Rollback**: re-publish a previously signed archived version (manifest +
   `source.js` together — they are pinned to each other by `scriptSha256`);
   the app transactionally re-points its local active-version pointer.
   Downgrades to never-signed bytes are refused on-device.

## Rules (script-specific, on top of the descriptor rules)

- `bridgeApi` must be supported by the app (`1` in v9.0.0); anything else is
  `INCOMPATIBLE`, not a schema error.
- `config` must be `{}` for scripts (whitelist is empty) — engine deviations
  for scripts live in code, not config.
- `source.js` executes with wall-clock + instruction + response-size quotas;
  entry results are validated into models (non-https page URLs, CRLF headers,
  NaN numbers all fail the call).
- The app runs scripts on a dedicated dispatcher off the main thread, with the
  interpreter lock (`optimizationLevel = -1`) enforced and tested.
