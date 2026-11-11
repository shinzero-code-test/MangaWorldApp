import { describe, expect, it } from "vitest";
import { readFileSync } from "fs";
import { fileURLToPath } from "url";
import path from "path";
import { NextRequest } from "next/server";
import { middleware, config } from "../middleware";

// U-31 residual — the nonced CSP must be the ONLY Content-Security-Policy
// the dashboard emits.
//
// The trap this file pins: a browser enforces the INTERSECTION of every
// CSP header it receives. A second, static policy cannot be a "fallback"
// for the nonced one — it either (a) carries 'unsafe-inline' and re-opens
// the hole the nonce closes, or (b) carries no nonce and blocks the very
// inline boot scripts the layout nonces. So the static headers must NOT
// define a script-src at all, and the middleware must own the policy.

const here = path.dirname(fileURLToPath(import.meta.url));
// src/lib/csp.test.ts → the dashboard project root is two levels up.
const dashboardRoot = path.resolve(here, "../..");

function readRoot(file: string): string {
  return readFileSync(path.join(dashboardRoot, file), "utf8");
}

/** The script-src directive of a policy, if it has one. */
function scriptSrc(policy: string): string | null {
  const match = /script-src([^;]*)/.exec(policy);
  return match ? match[1].trim() : null;
}

function policiesIn(source: string): string[] {
  return source.match(/script-src[^;]*/g) ?? [];
}

describe("nonced CSP (U-31)", () => {
  it("sets a nonce-based script-src without 'unsafe-inline' on every response", async () => {
    const res = middleware(new NextRequest("http://localhost/dashboard"));
    const csp = res.headers.get("content-security-policy") ?? "";

    expect(csp).toContain("script-src 'self' 'nonce-");
    expect(scriptSrc(csp)).not.toContain("'unsafe-inline'");
    // style-src keeps 'unsafe-inline' on purpose (Tailwind + GIS styles).
    expect(csp).toContain("style-src 'self' 'unsafe-inline'");
  });

  it("mints a FRESH nonce per request", async () => {
    const first = middleware(new NextRequest("http://localhost/dashboard"));
    const second = middleware(new NextRequest("http://localhost/dashboard"));
    const nonceOf = (res: Response) =>
      /nonce-([^']+)'/.exec(res.headers.get("content-security-policy") ?? "")?.[1];

    const a = nonceOf(first);
    const b = nonceOf(second);
    expect(a).toBeTruthy();
    expect(b).toBeTruthy();
    expect(a).not.toBe(b);
  });

  it("forwards the nonce to the page through the x-nonce request header", async () => {
    const res = middleware(new NextRequest("http://localhost/dashboard"));
    const forwarded = res.headers.get("x-middleware-request-x-nonce");
    // NextResponse.next() carries the request headers on the response object
    // for inspection; the CSP nonce and the forwarded one must be the same.
    const cspNonce = /nonce-([^']+)'/.exec(res.headers.get("content-security-policy") ?? "")?.[1];
    if (forwarded !== null) expect(forwarded).toBe(cspNonce);
    expect(cspNonce).toBeTruthy();
  });

  it("keeps the policy on public paths and on the unauthenticated redirect", async () => {
    const publicPath = middleware(new NextRequest("http://localhost/login"));
    expect(publicPath.headers.get("content-security-policy")).toContain("script-src 'self' 'nonce-");

    const redirect = middleware(new NextRequest("http://localhost/dashboard"));
    expect(redirect.status).toBe(307);
    expect(redirect.headers.get("location")).toBe("http://localhost/login");
    expect(redirect.headers.get("content-security-policy")).toContain("script-src 'self' 'nonce-");
  });

  it("covers every HTML/API path with the middleware matcher", () => {
    const matcher = new RegExp(`^${config.matcher[0]}$`);
    for (const p of ["/", "/login", "/dashboard", "/dashboard/users", "/api/users", "/api/settings"]) {
      expect(matcher.test(p)).toBe(true);
    }
    // Static assets stay out of the edge middleware (they carry no HTML).
    for (const p of ["/_next/static/chunk.js", "/favicon.ico", "/sw.js"]) {
      expect(matcher.test(p)).toBe(false);
    }
  });

  it("has NO static script-src policy left in next.config.ts or vercel.json", () => {
    const nextConfig = readRoot("next.config.ts");
    const vercel = JSON.parse(readRoot("vercel.json")) as {
      headers: Array<{ source: string; headers: Array<{ key: string; value: string }> }>;
    };

    // 1. The catch-all block must not carry a CSP at all — it would
    //    intersect with the nonced middleware policy on every page.
    const catchAll = nextConfig.split('source: "/(.*)"')[1]?.split("source:")[0] ?? "";
    expect(catchAll).not.toContain("Content-Security-Policy");
    const catchAllVercel = vercel.headers.find((h) => h.source === "/(.*)");
    expect(catchAllVercel?.headers.some((h) => h.key === "Content-Security-Policy")).toBe(false);

    // 2. Any policy that is left is scoped to a matcher-excluded asset and
    //    never allows inline script.
    const matcher = new RegExp(`^${config.matcher[0]}$`);
    for (const entry of vercel.headers) {
      for (const header of entry.headers) {
        if (header.key !== "Content-Security-Policy") continue;
        expect(
          matcher.test(entry.source),
          `${entry.source} is served by the middleware — a second policy would intersect`,
        ).toBe(false);
        const src = scriptSrc(header.value);
        if (src !== null) expect(src).not.toContain("'unsafe-inline'");
      }
    }
    for (const policy of policiesIn(nextConfig)) {
      expect(policy).not.toContain("'unsafe-inline'");
    }
  });

  it("nonces both inline boot scripts in the layout", () => {
    const layout = readRoot("src/app/layout.tsx");
    const inlineScripts = layout.match(/<script[^>]*>/g) ?? [];
    expect(inlineScripts.length).toBe(2);
    for (const tag of inlineScripts) {
      expect(tag).toContain("nonce={nonce}");
    }
    // The nonce comes from the middleware header, not from a constant.
    expect(layout).toContain('headers()).get("x-nonce")');
  });
});
