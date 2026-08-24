import { NextRequest, NextResponse } from "next/server";

const PUBLIC_PATHS = new Set(["/login", "/api/auth/login", "/api/auth/google"]);

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // Allow public paths — exact match on the first segment so "/loginfoo" etc.
  // doesn't accidentally inherit the exemption (L-7).
  const firstSegment = "/" + (pathname.split("/")[1] ?? "");
  if (PUBLIC_PATHS.has(pathname) || PUBLIC_PATHS.has(firstSegment)) {
    return NextResponse.next();
  }

  // Check session cookie
  const session = request.cookies.get("session");
  if (!session?.value && !pathname.startsWith("/api/")) {
    return NextResponse.redirect(new URL("/login", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    "/((?!_next/static|_next/image|favicon.ico|.*\\.png$|.*\\.svg$).*)",
  ],
};
