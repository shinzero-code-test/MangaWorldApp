import { NextRequest, NextResponse } from "next/server";
import { verifyAppIdToken } from "@/lib/app-auth";
import { consumeRateLimit } from "@/lib/security";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

const MAX_TEXT_LENGTH = 2_000;

/**
 * POST /api/community/moderate
 *
 * Server-side content gate for community text (comments, replies, reviews,
 * chat). The Android app previously enforced banned-keywords purely via Remote
 * Config inside the client — a repackaged APK could skip it entirely. This
 * endpoint re-checks the same keywords where they live: the published Remote
 * Config template, read with the Admin SDK so client tampering is irrelevant.
 *
 * Fail-open by design on transient errors: post-hoc moderationReports remain
 * the backstop, and blocking all posting during an outage would be worse.
 */
export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);

    const limiter = await consumeRateLimit("community-moderate", user.uid, 60, 60 * 1000);
    if (!limiter.allowed) {
      return NextResponse.json({ error: "Too many requests" }, { status: 429 });
    }

    const body = await request.json().catch(() => null);
    const text = typeof body?.text === "string" ? body.text : "";
    if (!text) {
      return NextResponse.json({ error: "text is required" }, { status: 400 });
    }
    if (text.length > MAX_TEXT_LENGTH) {
      return NextResponse.json({ allowed: false, reason: "too_long" });
    }

    const template = await getAdminRemoteConfig().getTemplate();
    const paramValue = template.parameters?.["community_banned_keywords"]?.defaultValue;
    // RemoteConfigParameterValue is a union — only conditional/default values carry `.value`.
    const rawKeywords =
      paramValue && "value" in paramValue ? paramValue.value : "";
    const keywords = String(rawKeywords)
      .split(",")
      .map((k) => k.trim().toLowerCase())
      .filter(Boolean);

    if (keywords.length > 0) {
      const normalized = text.toLowerCase();
      if (keywords.some((keyword) => normalized.includes(keyword))) {
        return NextResponse.json({ allowed: false, reason: "banned_keyword" });
      }
    }

    return NextResponse.json({ allowed: true });
  } catch (error) {
    console.error("[community/moderate] failure:", error instanceof Error ? error.message : error);
    // Fail-open: never block community participation because the checker hiccuped.
    return NextResponse.json({ allowed: true });
  }
}
