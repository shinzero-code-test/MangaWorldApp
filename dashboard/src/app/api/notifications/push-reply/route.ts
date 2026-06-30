import { NextRequest, NextResponse } from "next/server";
import { getAdminMessaging, getAdminDb } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

/**
 * Sends a targeted push notification to a specific user when someone
 * replies to their comment or mentions them.
 *
 * POST body:
 *   targetUid: string   — the notification recipient's Firebase UID
 *   title: string       — notification title
 *   body: string        — notification body text
 *   mangaId?: string    — for deep-link navigation
 *   slug?: string
 *   sourceId?: string
 *   chapterUrl?: string
 */
export async function POST(request: NextRequest) {
  try {
    const { targetUid, title, body: msgBody, mangaId, slug, sourceId, chapterUrl } =
      await request.json();

    if (!targetUid || !title || !msgBody) {
      return NextResponse.json(
        { error: "targetUid, title, and body are required" },
        { status: 400 }
      );
    }

    // Look up all device tokens for this user
    const devicesSnap = await getAdminDb()
      .collection("users")
      .doc(targetUid)
      .collection("devices")
      .get();

    const tokens = devicesSnap.docs
      .map((d) => d.data().token as string)
      .filter(Boolean);

    if (tokens.length === 0) {
      return NextResponse.json({ success: true, sent: 0, message: "No devices for user" });
    }

    const data: Record<string, string> = {};
    if (mangaId) data.mangaId = mangaId;
    if (slug) data.slug = slug;
    if (sourceId) data.sourceId = sourceId;
    if (chapterUrl) data.chapterUrl = chapterUrl;

    let successCount = 0;
    let failureCount = 0;

    // FCM multicast limit is 500 tokens per request
    for (let i = 0; i < tokens.length; i += 500) {
      const chunk = tokens.slice(i, i + 500);
      const res = await getAdminMessaging().sendEachForMulticast({
        tokens: chunk,
        notification: { title, body: msgBody },
        data,
      });
      successCount += res.successCount;
      failureCount += res.failureCount;

      // Clean up invalid tokens
      res.responses.forEach((r, idx) => {
        if (!r.success && r.error?.code === "messaging/registration-token-not-registered") {
          const invalidToken = chunk[idx];
          // Find and delete the invalid token document
          const invalidDoc = devicesSnap.docs.find((d) => d.data().token === invalidToken);
          if (invalidDoc) invalidDoc.ref.delete().catch(() => {});
        }
      });
    }

    return NextResponse.json({ success: true, sent: successCount, failed: failureCount });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
