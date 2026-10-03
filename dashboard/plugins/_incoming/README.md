# `_incoming/` — contributor staging (NOT served, NOT synced)

Drop candidate payloads here for review. Nothing in this directory is reachable
over HTTP and the app never reads it — promotion is a deliberate human act.

## Publish flow (human sign-off required)

1. **Stage**: place the candidate `plugin.json` (+ `source.js` for script kinds)
   under `_incoming/<id>/v<version>/`, with captured fixtures.
2. **CI validates**: schema + semantics, Ed25519 signature against the pinned
   `official-1` key (signed OFFLINE with the private key from Vercel
   `PLUGIN_SIGNING_PRIVATE_KEY_B64` — never committed), fixture smoke through
   the real engine on JVM.
3. **Human reviewer approves** (CI passing is necessary but NOT sufficient).
4. **Promote**: copy the signed version into
   `dashboard/public/plugins/<id>/v<version>/`, move the superseded version to
   `dashboard/plugins/_archive/<id>/`, bump `public/plugins/index.json`
   (`version` + `updatedAt`).
5. **Deploy**: git auto-deploy is OFF (`vercel.json`) — run `vercel --prod`
   from `dashboard/` and verify the live `/plugins/index.json` bytes
   (`updatedAt`/ETag) before announcing. A merged-but-undeployed `index.json`
   changes nothing the fleet polls.
6. **Rollback**: re-publish a previously signed archived manifest/version (or
   lower the index `version` back); the app transactionally re-points its local
   active-version pointer. Downgrades to never-signed versions are refused
   on-device.

## Rules

- `id` is immutable and must equal the stored `sourceId`. NEVER rename.
- `allowedHosts` entries are literal hostnames only (no wildcards in v1).
- `requiresPermission` sources (robots-gated APIs) are never auto-enabled.
- Promote requires the dashboard's standard admin guard (role + MFA).
- Pilot descriptors (`hijala`/`lavascans` v1) are frozen: the app reinstalls
  the APK asset every boot, so correcting a shipped v1 manifest requires a
  version bump — re-publishing v1 bytes is refused on-device as immutable.
- `updatedAt`-only index re-touches do NOT re-drive `UpToDate`/held entries
  on device; use a version bump (new behavior to verify) or explicit approval.
- Trust calendar: manifests carry `issuedAt` and downloaded trust older
  than 90 days is rejected. Re-issue (new `issuedAt` + version bump +
  re-sign) well before expiry — same-version bytes can never be re-signed
  (immutability). Current anchors: `hijala`/`lavascans` 2026-09-24,
  `manonga` v2 2026-10-03.
- `manonga` ships `enabledByDefault: false` (dogfood-only): fresh installs
  land DISABLED until opted in — distinguish opt-in state from sync failure
  in fleet reports.
