import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  serverExternalPackages: ["firebase-admin"],
  // Lint runs via `npm run lint` (real core-web-vitals flat config).
  // Builds keep ignoring it until the remaining `any`-debt is typed (F6).
  eslint: {
    ignoreDuringBuilds: true,
  },
  poweredByHeader: false,
  // Mirror of vercel.json headers: self-hosted/preview runtimes must keep
  // the same posture when Vercel edge headers don't apply.
  async headers() {
    return [
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
          {
            key: "Content-Security-Policy",
            value:
              "default-src 'self'; script-src 'self' 'unsafe-inline' https://accounts.google.com https://apis.google.com https://www.gstatic.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self' https://*.googleapis.com https://*.cloudinary.com https://firebaseinstallations.googleapis.com https://accounts.google.com; frame-src https://mangaworld-live-260519.firebaseapp.com https://*.firebaseapp.com https://accounts.google.com; child-src https://mangaworld-live-260519.firebaseapp.com https://*.firebaseapp.com https://accounts.google.com; frame-ancestors 'none'",
          },
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
