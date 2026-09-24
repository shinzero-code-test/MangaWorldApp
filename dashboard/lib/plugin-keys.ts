/**
 * Source plugin trust pins (mirror of the app's `PluginTrust`).
 *
 * PUBLIC halves only — safe to commit. The private signing key lives in the
 * `PLUGIN_SIGNING_PRIVATE_KEY_B64` Vercel env var (production) plus the
 * gitignored `tmp/secrets/` local backup. Never add private key material here.
 *
 * The publish pipeline (Phase 2B) signs canonical `plugin.json` bytes with the
 * private key, then independently verifies with the public pin below before
 * promoting — same wire format the app verifies with Tink.
 */
export const PLUGIN_SIGNING_KEY_ID = "official-1";

/** Raw 32-byte Ed25519 public key, base64. */
export const PLUGIN_SIGNING_PUBLIC_KEY_B64 =
  "o01gRyLfjV9Zxuo8rOxYB/kdMvPt9mLHbv/tKN9k9FM=";

/** Firebase Remote Config transport key for cross-signed rotation announcements. */
export const PLUGIN_ROTATION_RC_KEY = "plugin_key_rotation";
