import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

type Params = Promise<{ collection: string; docId: string }>;

export async function GET(_: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    const doc = await getAdminDb().collection(collection).doc(docId).get();
    if (!doc.exists) return NextResponse.json({ error:"المستند غير موجود" }, { status:404 });
    return NextResponse.json({ id:doc.id, fields:doc.data() });
  } catch (e:any) { return NextResponse.json({ error:e.message }, { status:500 }); }
}

export async function PUT(request: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    const data = await request.json();
    await getAdminDb().collection(collection).doc(docId).set(data, { merge:true });
    return NextResponse.json({ success:true });
  } catch (e:any) { return NextResponse.json({ error:e.message }, { status:500 }); }
}

export async function DELETE(_: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    await getAdminDb().collection(collection).doc(docId).delete();
    return NextResponse.json({ success:true });
  } catch (e:any) { return NextResponse.json({ error:e.message }, { status:500 }); }
}
