import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  serverExternalPackages: ["firebase-admin"],
  // Lint gates in CI (`npm run lint`: 0 errors, only <img> warnings) and
  // runs on every build — warnings never fail a build, errors do.
  poweredByHeader: false,
  // Mirror of vercel.json headers: self-hosted/preview runtimes must keep
  // the same posture when Vercel edge headers don't apply.
  //
  // U-31: the authoritative CSP is the NONCED policy set by middleware.ts
  // per request (static headers physically cannot carry a fresh nonce).
  // There is deliberately NO static Content-Security-Policy here: a second
  // CSP header does not "fallback" — the browser enforces the INTERSECTION
  // of every policy it receives, so a static policy without the per-request
  // nonce would block the nonced boot scripts (and with 'unsafe-inline' it
  // would have re-opened the very hole the nonce closes). The middleware
  // matcher covers every HTML/JS/API response; only static assets
  // (_next/static, images, favicon) are excluded, and those get the strict
  // same-origin policy below.
  async headers() {
    return [
      // Plugin distribution must revalidate (mirrors vercel.json
      // /plugins/*): promotions/revokes are polled by ETag.
      {
        source: "/plugins/:path*",
        headers: [{ key: "Cache-Control", value: "no-cache, must-revalidate" }],
      },
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=(), identity-credentials-get=(self)",
          },
          {
            key: "Strict-Transport-Security",
            value: "max-age=63072000; includeSubDomains; preload",
          },
        ],
      },
      // The service worker and the web-app manifest are excluded from the
      // middleware matcher (they are static files), so they carry their own
      // strict policy instead of the nonced one.
      {
        source: "/sw.js",
        headers: [
          { key: "Content-Security-Policy", value: "default-src 'self'; script-src 'self'; object-src 'none'" },
        ],
      },
      {
        source: "/manifest.json",
        headers: [
          { key: "Content-Security-Policy", value: "default-src 'self'; object-src 'none'" },
        ],
      },
    ];
  },
  images: {
    remotePatterns: [
      { protocol:"https", hostname:"lh3.googleusercontent.com" },
      { protocol:"https", hostname:"firebasestorage.googleapis.com" },
    ],
  },
};

export default nextConfig;
