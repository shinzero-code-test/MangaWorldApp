import { NextRequest, NextResponse } from "next/server";
import { getAdminMessaging, getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { boundedString, isPlainObject } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const raw = await request.json();
    if (!isPlainObject(raw)) {
      return NextResponse.json({ error: "Invalid body" }, { status: 400 });
    }
    // FCM-aligned bounds; validated before touching the Messaging API (M-6).
    const title = boundedString(raw.title, 120);
    const msgBody = boundedString(raw.body, 300);
    if (title === null || msgBody === null) {
      return NextResponse.json({ error: "title/body required (max 120 / 300 chars)" }, { status: 400 });
    }

    let tokens: string[] | null = null;
    if (raw.tokens != null) {
      if (!Array.isArray(raw.tokens) || raw.tokens.length > 500 ||
          !raw.tokens.every((t: unknown) => typeof t === "string" && t.length >= 10)) {
        return NextResponse.json({ error: "invalid tokens array" }, { status: 400 });
      }
      tokens = raw.tokens as string[];
    }

    // If tokens are explicitly provided (e.g. testing or specific users)
    if (tokens && tokens.length > 0) {
      const response = await getAdminMessaging().sendEachForMulticast({
        tokens, notification: { title, body: msgBody },
      });
      return NextResponse.json({ success: true, sent: response.successCount, failed: response.failureCount });
    }

    // Since Android app doesn't subscribe to global topics, we must multicast to all devices
    const devicesSnap = await getAdminDb().collectionGroup("devices").get();
    const allTokens = Array.from(new Set(devicesSnap.docs.map(d => d.data().token).filter(Boolean)));
    
    if (allTokens.length === 0) {
      return NextResponse.json({ success: true, sent: 0, failed: 0, message: "No devices found" });
    }

    let successCount = 0;
    let failureCount = 0;
    
    // FCM allows max 500 tokens per multicast
    for (let i = 0; i < allTokens.length; i += 500) {
      const chunk = allTokens.slice(i, i + 500);
      const res = await getAdminMessaging().sendEachForMulticast({
        tokens: chunk,
        notification: { title, body: msgBody },
      });
      successCount += res.successCount;
      failureCount += res.failureCount;
    }

    return NextResponse.json({ success: true, sent: successCount, failed: failureCount });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
