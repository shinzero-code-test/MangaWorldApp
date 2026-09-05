import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

const REPO = "shinzero-code-test/MangaWorldApp";

// Short in-memory cache: GitHub's unauthenticated quota is 60 req/hr/IP.
let cached: { at: number; releases: unknown[] } | null = null;
const CACHE_TTL_MS = 10 * 60 * 1000;

interface GithubRelease {
  id: number;
  tag_name?: string;
  name?: string | null;
  body?: string | null;
  published_at?: string | null;
  draft?: boolean;
  prerelease?: boolean;
  assets?: { download_count?: number }[];
}

/**
 * GET /api/releases — real app releases from GitHub (the project's
 * release pipeline publishes APKs/AABs here on every version tag).
 */
export async function GET() {
  try {
    await requireRole("moderator");
    if (cached && Date.now() - cached.at < CACHE_TTL_MS) {
      return NextResponse.json({ releases: cached.releases });
    }
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=30`, {
      headers: { Accept: "application/vnd.github+json", "User-Agent": "mangaworld-admin" },
    });
    if (!res.ok) {
      if (cached) return NextResponse.json({ releases: cached.releases, stale: true });
      return NextResponse.json({ error: "تعذر جلب الإصدارات من GitHub" }, { status: 502 });
    }
    const data = (await res.json()) as GithubRelease[];
    const releases = (Array.isArray(data) ? data : [])
      .filter((r) => !r.draft)
      .map((r) => {
        const version = (r.tag_name ?? "").replace(/^v/, "");
        const buildMatch = /(\d+)\s*$/.exec(r.body ?? "") ?? /build\s*(\d+)/i.exec(r.body ?? "");
        return {
          id: String(r.id),
          version,
          buildNumber: buildMatch ? Number(buildMatch[1]) : 0,
          platform: "android" as const,
          notes: (r.body ?? "").slice(0, 2000),
          publishedAt: r.published_at ?? "",
          status: r.prerelease ? "draft" : "active",
          downloads: (r.assets ?? []).reduce((s, a) => s + (a.download_count ?? 0), 0),
        };
      });
    cached = { at: Date.now(), releases };
    return NextResponse.json({ releases });
  } catch (error: unknown) {
    if (cached) return NextResponse.json({ releases: cached.releases, stale: true });
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
