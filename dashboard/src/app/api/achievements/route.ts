import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("viewer");
    const doc = await adminDb.collection("user_achievements").doc("local_user").get();
    return NextResponse.json({ achievements: doc.data() || {} });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { data } = await request.json();
    await adminDb.collection("user_achievements").doc("local_user").set({ ...data, lastUpdated: Date.now() }, { merge: true });
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
