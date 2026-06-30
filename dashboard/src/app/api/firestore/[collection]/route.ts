import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

type Params = Promise<{ collection: string }>;

export async function GET(request: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    const { searchParams } = new URL(request.url);
    const limit = parseInt(searchParams.get("limit") || "50");
    const snap  = await getAdminDb().collection(collection).limit(limit).get();
    const documents = snap.docs.map(d => ({ id:d.id, fields:d.data() }));
    return NextResponse.json({ documents });
  } catch (e:any) { return NextResponse.json({ error:e.message }, { status:500 }); }
}

export async function POST(request: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    const { id, data }   = await request.json();
    let ref;
    if (id) { ref = getAdminDb().collection(collection).doc(id); await ref.set(data); }
    else     { ref = await getAdminDb().collection(collection).add(data); }
    return NextResponse.json({ id: ref.id });
  } catch (e:any) { return NextResponse.json({ error:e.message }, { status:500 }); }
}
