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
5. **Rollback**: re-publish a previously signed archived manifest/version (or
   lower the index `version` back); the app transactionally re-points its local
   active-version pointer. Downgrades to never-signed versions are refused
   on-device.

## Rules

- `id` is immutable and must equal the stored `sourceId`. NEVER rename.
- `allowedHosts` entries are literal hostnames only (no wildcards in v1).
- `requiresPermission` sources (robots-gated APIs) are never auto-enabled.
- Promote requires the dashboard's standard admin guard (role + MFA).
