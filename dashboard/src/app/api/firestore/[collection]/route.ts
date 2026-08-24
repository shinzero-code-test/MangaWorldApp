import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

/**
 * Whitelist of collections the super-admin data browser may touch. Prevents the dynamic
 * route from becoming an arbitrary read/write/delete path into sensitive collections
 * (admin2fa, adminMfaSessions, email_registry, ...). Keep in sync with the dashboard
 * data-browser page's COLLECTIONS list.
 */
const ALLOWED_COLLECTIONS = new Set([
  "publicProfiles",
  "community_manga",
  "moderationReports",
  "user_achievements",
  "cloudinaryAssets",
]);

type Params = Promise<{ collection: string }>;

function isAllowed(collection: string): boolean {
  return ALLOWED_COLLECTIONS.has(collection);
}

export async function GET(request: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    if (!isAllowed(collection)) {
      return NextResponse.json({ error: "Collection not allowed" }, { status: 400 });
    }
    const { searchParams } = new URL(request.url);
    const limit = Math.min(Math.max(parseInt(searchParams.get("limit") || "50", 10) || 50, 1), 200);
    const snap  = await getAdminDb().collection(collection).limit(limit).get();
    const documents = snap.docs.map(d => ({ id:d.id, fields:d.data() }));
    return NextResponse.json({ documents });
  } catch (e:any) { return NextResponse.json({ error:e.message }, { status:500 }); }
}

export async function POST(request: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    if (!isAllowed(collection)) {
      return NextResponse.json({ error: "Collection not allowed" }, { status: 400 });
    }
    const { id, data }   = await request.json();
    let ref;
    if (id) { ref = getAdminDb().collection(collection).doc(id); await ref.set(data); }
    else     { ref = await getAdminDb().collection(collection).add(data); }
    return NextResponse.json({ id: ref.id });
  } catch (e:any) { return NextResponse.json({ error:e.message }, { status:500 }); }
}
