import { NextRequest, NextResponse } from "next/server";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { boundedString } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

// Single source of truth for moderation keywords: Remote Config
// `community_banned_keywords` — the exact key the Android app
// (FirebaseRemoteConfigManager) and /api/community/moderate read.
// (Previously this route used Firestore app_config/banned_keywords, which
// nothing reads — edits were silent no-ops.)
const RC_KEY = "community_banned_keywords";

function readKeywords(template: { parameters?: Record<string, { defaultValue?: unknown }> }): string {
  const def = template.parameters?.[RC_KEY]?.defaultValue;
  return def && typeof def === "object" && "value" in def ? String((def as { value: unknown }).value ?? "") : "";
}

export async function GET() {
  try {
    await requireRole("super-admin");
    const template = await getAdminRemoteConfig().getTemplate();
    return NextResponse.json({ keywords: readKeywords(template) });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const body = await request.json();
    // Comma-separated keyword list, bounded so a stray payload can't bloat
    // the template. Empty string is allowed — it clears the list.
    const raw = body?.keywords;
    if (typeof raw !== "string" || raw.length > 10_000) {
      return NextResponse.json({ error: "keywords must be a string (max 10k chars)" }, { status: 400 });
    }
    const keywords = boundedString(raw, 10_000) ?? "";
    const rc = getAdminRemoteConfig();
    const template = await rc.getTemplate();
    template.parameters[RC_KEY] = {
      defaultValue: { value: keywords },
      valueType: "STRING",
    };
    await rc.publishTemplate(template);
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
