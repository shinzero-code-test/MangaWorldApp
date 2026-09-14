import { NextRequest, NextResponse } from "next/server";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

/**
 * FOLLOW fan-out. The app writes the relationships batch directly from the
 * client, then calls here best-effort. The server verifies the relationship
 * exists (prevents forged notifies) and creates the FOLLOW notification +
 * FCM via the Admin SDK — clients cannot write to another user's
 * `users/{uid}/notifications` (owner-only rule).
 *
 * Deterministic doc ID `follow_{followerUid}`: repeat follows upsert instead
 * of spamming. Unfollow keeps history (no delete).
 */
export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    rejectAnonymousUser(user);
    if (!(await allowAppMutation(`follow-notify:${user.uid}`, 60, 60 * 1000))) {
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }
    const { targetUid } = await request.json();
    if (
      typeof targetUid !== "string" ||
      targetUid.length < 1 ||
      targetUid.length > 128 ||
      targetUid.includes("/") ||
      targetUid === user.uid
    ) {
      return NextResponse.json({ error: "حدث متابعة غير صالح" }, { status: 400 });
    }

    const db = getAdminDb();
    const rel = await db
      .collection("relationships")
      .doc(user.uid)
      .collection("following")
      .doc(targetUid)
      .get();
    if (!rel.exists) {
      return NextResponse.json({ error: "المتابعة غير موجودة" }, { status: 404 });
    }

    const me = (await db.collection("publicProfiles").doc(user.uid).get()).data();
    const myName = String(me?.displayName || me?.username || "مستخدم");
    const notifRef = db
      .collection("users")
      .doc(targetUid)
      .collection("notifications")
      .doc(`follow_${user.uid}`);
    await notifRef.set(
      {
        id: notifRef.id,
        type: "FOLLOW",
        title: "متابِع جديد",
        body: `${myName} بدأ بمتابعتك`,
        mangaId: "",
        slug: "",
        sourceId: "",
        chapterUrl: null,
        commentId: null,
        createdAt: Date.now(),
        read: false,
      },
      { merge: true }
    );

    try {
      const devices = await db.collection("users").doc(targetUid).collection("devices").get();
      const tokens = devices.docs
        .map((d) => d.data().token)
        .filter((t): t is string => typeof t === "string");
      if (tokens.length > 0) {
        await getAdminMessaging().sendEachForMulticast({
          tokens: tokens.slice(0, 500),
          data: { title: "متابِع جديد", body: `${myName} بدأ بمتابعتك`, type: "FOLLOW" },
        });
      }
    } catch {
      /* FCM is best-effort — the notification doc is already committed */
    }
    return NextResponse.json({ success: true });
  } catch (error) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
