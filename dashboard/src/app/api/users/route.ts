import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { searchParams } = new URL(request.url);
    const search = searchParams.get("search") || "";
    const role = searchParams.get("role") || "";
    const page = parseInt(searchParams.get("page") || "1");
    const limit = parseInt(searchParams.get("limit") || "20");
    const offset = (page - 1) * limit;

    let query: any = adminDb.collection("publicProfiles");
    if (role) query = query.where("role", "==", role);

    const snapshot = await query.orderBy("updatedAt", "desc").offset(offset).limit(limit + 1).get();
    const users = snapshot.docs.slice(0, limit).map((doc: any) => ({ id: doc.id, ...doc.data() }));
    const hasMore = snapshot.docs.length > limit;

    let filtered = users;
    if (search) {
      const s = search.toLowerCase();
      filtered = users.filter((u: any) => u.username?.toLowerCase().includes(s) || u.email?.toLowerCase().includes(s));
    }

    return NextResponse.json({ users: filtered, total: filtered.length, hasMore });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
