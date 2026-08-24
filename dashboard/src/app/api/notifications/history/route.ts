import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { boundedString, isPlainObject } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const snap = await getAdminDb().collection("notification_history")
      .orderBy("sentAt", "desc").limit(50).get();
    const history = snap.docs.map((doc: any) => ({ id: doc.id, ...doc.data() }));
    return NextResponse.json({ history });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function POST(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const raw = await request.json();
    if (!isPlainObject(raw)) {
      return NextResponse.json({ error: "Invalid body" }, { status: 400 });
    }
    // Bounded shapes so oversized entries can't land in Firestore (M-6).
    const title = boundedString(raw.title, 200);
    const body = boundedString(raw.body, 2_000);
    if (title === null || body === null) {
      return NextResponse.json({ error: "title/body required (max 200 / 2000 chars)" }, { status: 400 });
    }
    const topic = raw.topic == null ? null : boundedString(raw.topic, 120);
    if (raw.topic != null && topic === null) {
      return NextResponse.json({ error: "invalid topic" }, { status: 400 });
    }
    const targetUids = raw.targetUids == null ? null : raw.targetUids;
    if (targetUids !== null && !(Array.isArray(targetUids) && targetUids.length <= 500 && targetUids.every((u: unknown) => typeof u === "string" && u.length > 0))) {
      return NextResponse.json({ error: "invalid targetUids" }, { status: 400 });
    }

    // Save to history
    const entry = {
      title,
      body,
      topic,
      targetUids,
      sentAt: Date.now(),
      sentBy: "admin",
      status: "sent",
    };

    await getAdminDb().collection("notification_history").add(entry);

    return NextResponse.json({ success: true, entry });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
