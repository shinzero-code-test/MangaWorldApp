import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("super-admin");
    const snap = await adminDb.collection("notification_history")
      .orderBy("sentAt", "desc").limit(50).get();
    const history = snap.docs.map((doc: any) => ({ id: doc.id, ...doc.data() }));
    return NextResponse.json({ history });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function POST(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { title, body, topic, targetUids } = await request.json();

    // Save to history
    const entry = {
      title,
      body,
      topic: topic || null,
      targetUids: targetUids || null,
      sentAt: Date.now(),
      sentBy: "admin",
      status: "sent",
    };

    await adminDb.collection("notification_history").add(entry);

    return NextResponse.json({ success: true, entry });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
