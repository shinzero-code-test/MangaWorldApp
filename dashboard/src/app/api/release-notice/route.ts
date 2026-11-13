import { NextResponse } from "next/server";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

const FALLBACK_RELEASE_URL = "https://github.com/shinzero-code-test/MangaWorldApp/releases/latest";

/**
 * GET /api/release-notice — the release floor, for the LOGIN page.
 *
 * Track A of `dashboard/scripts/README-release-policy.md` requires a floor
 * raise to be announced where the admin sees it first (the dashboard is
 * where the admin lands). The value is read from the LIVE Remote Config
 * template — the very same source the app reads — so the notice can never
 * drift from the floor the fleet is actually gated on.
 *
 * Public on purpose: the floor and the release URL are public (the release
 * page states the same), and the login page is by definition reachable
 * without a session. Nothing else from the template is exposed.
 */
export async function GET() {
  try {
    const template = await getAdminRemoteConfig().getTemplate();
    const read = (key: string): string => {
      // The SDK types `defaultValue` as a plain string, but the API also
      // returns the `{ value: "..." }` shape — so it is read as `unknown`.
      const def: unknown = template.parameters?.[key]?.defaultValue;
      if (typeof def === "string") return def.trim();
      if (def && typeof def === "object" && "value" in def) {
        return String((def as { value?: unknown }).value ?? "").trim();
      }
      return "";
    };
    // Only an https URL is ever shown as a link — the value comes from a
    // remote source, and a `javascript:` URL in an href is an XSS vector.
    const rawUrl = read("update_url");
    const releaseUrl = rawUrl.startsWith("https://") ? rawUrl : FALLBACK_RELEASE_URL;
    return NextResponse.json({
      minSupportedVersion: read("min_supported_version") || null,
      latestVersion: read("latest_version") || null,
      releaseUrl,
    });
  } catch {
    // Fail-soft: a notice that cannot be read must never break the login
    // page — the admin can always read the floor in the RC screen instead.
    return NextResponse.json({
      minSupportedVersion: null,
      latestVersion: null,
      releaseUrl: FALLBACK_RELEASE_URL,
    });
  }
}
