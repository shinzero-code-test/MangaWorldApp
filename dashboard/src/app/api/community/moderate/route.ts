import { NextRequest, NextResponse } from "next/server";
import { verifyAppIdToken } from "@/lib/app-auth";
import { consumeRateLimit } from "@/lib/security";
import { isTextAllowed } from "@/lib/moderation";

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
  // Auth + validation are fail-CLOSED: an invalid token or bad body must
  // never yield allowed:true. Only the keyword-scan itself (Remote Config
  // fetch) is fail-open, so a template outage can't silence the community.
  let user;
  try {
    user = await verifyAppIdToken(request);
  } catch {
    return NextResponse.json({ error: "غير مصرح" }, { status: 401 });
  }

  try {
    const limiter = await consumeRateLimit("community-moderate", user.uid, 60, 60 * 1000);
    if (!limiter.allowed) {
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }

    const body = await request.json().catch(() => null);
    const text = typeof body?.text === "string" ? body.text.trim() : "";
    if (!text) {
      return NextResponse.json({ error: "النص مطلوب" }, { status: 400 });
    }
    if (text.length > MAX_TEXT_LENGTH) {
      return NextResponse.json({ allowed: false, reason: "too_long" });
    }

    if (!(await isTextAllowed(text))) {
      return NextResponse.json({ allowed: false, reason: "banned_keyword" });
    }
    return NextResponse.json({ allowed: true });
  } catch (error) {
    console.error("[community/moderate] failure:", error instanceof Error ? error.message : error);
    return NextResponse.json({ error: "خطأ غير متوقع" }, { status: 500 });
  }
}
