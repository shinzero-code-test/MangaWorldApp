import { NextRequest, NextResponse } from "next/server";

const PUBLIC_PATHS = new Set([
  "/login",
  "/api/auth/login",
  "/api/auth/google",
  "/api/auth/google-credential",
  // Signed plugin distribution (plan §6/§10): index.json, manifests,
  // source.js, fixtures, logos are PUBLIC BY DESIGN — authenticity comes
  // from Ed25519 signatures verified on-device, never from access control.
  // The app polls these anonymously (no session cookie exists on devices).
  // Never place unpublished/review material here: _incoming/ and _archive/
  // live outside public/ and are not served.
  "/plugins",
]);

// U-31: nonced CSP. Static headers (next.config.ts / vercel.json) CANNOT
// carry a per-request nonce — this middleware is the authoritative policy:
// it mints a fresh nonce per request, forwards it to the layout via x-nonce
// (so RootLayout can nonce its inline scripts AND Next.js framework scripts
// inherit the same nonce), and sets the response CSP with that nonce.
// script-src drops 'unsafe-inline' here; style-src keeps it (Tailwind +
// Google Identity Services button styles require it).
function generateNonce(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function cspHeader(nonce: string): string {
  return `default-src 'self'; script-src 'self' 'nonce-${nonce}' https://accounts.google.com https://apis.google.com https://www.gstatic.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self' https://*.googleapis.com https://*.cloudinary.com https://firebaseinstallations.googleapis.com https://accounts.google.com; frame-src https://mangaworld-live-260519.firebaseapp.com https://*.firebaseapp.com https://accounts.google.com; child-src https://mangaworld-live-260519.firebaseapp.com https://*.firebaseapp.com https://accounts.google.com; frame-ancestors 'none'`;
}

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const nonce = generateNonce();
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);

  const withHeaders = (response: NextResponse) => {
    response.headers.set("Content-Security-Policy", cspHeader(nonce));
    return response;
  };

  // Allow public paths — exact match on the first segment so "/loginfoo" etc.
  // doesn't accidentally inherit the exemption (L-7).
  const firstSegment = "/" + (pathname.split("/")[1] ?? "");
  if (PUBLIC_PATHS.has(pathname) || PUBLIC_PATHS.has(firstSegment)) {
    return withHeaders(NextResponse.next({ request: { headers: requestHeaders } }));
  }

  // Check session cookie
  const session = request.cookies.get("session");
  if (!session?.value && !pathname.startsWith("/api/")) {
    return withHeaders(NextResponse.redirect(new URL("/login", request.url)));
  }

  return withHeaders(NextResponse.next({ request: { headers: requestHeaders } }));
}

export const config = {
  matcher: [
    "/((?!_next/static|_next/image|favicon.ico|manifest.json|sw.js|.*\\.png$|.*\\.svg$).*)",
  ],
};
