import { NextRequest, NextResponse } from "next/server";
import { v2 as cloudinary } from "cloudinary";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb } from "@/lib/firebase-admin";
import { cloudinaryAssetId } from "@/lib/cloudinary-assets";
import { genericErrorResponse } from "@/lib/security";

cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  let user;
  try {
    user = await verifyAppIdToken(request);
    rejectAnonymousUser(user);
  } catch (authError) {
    const { body, status } = genericErrorResponse(authError);
    return NextResponse.json(body, { status });
  }
  try {
    if (!(await allowAppMutation(`delete:${user.uid}`, 30, 60 * 60 * 1000))){
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }
    const { publicId } = await request.json();
    if (typeof publicId !== "string" || !/^[A-Za-z0-9_\-/]{1,200}$/.test(publicId)) {
      return NextResponse.json({ error: "معرف الأصل غير صالح" }, { status: 400 });
    }

    const assetRef = getAdminDb().collection("cloudinaryAssets").doc(cloudinaryAssetId(publicId));
    const asset = await assetRef.get();
    const owned = asset.exists && asset.data()?.uid === user.uid && asset.data()?.publicId === publicId;
    // Orphan fallback: a tracked row may be missing (failed write, legacy
    // asset) while the bytes still exist under the caller's own prefix.
    // Prefix-scoped self-delete can only touch the caller's folder.
    const orphanOwned = !asset.exists && publicId.startsWith(`app/${user.uid}/`);
    if (!owned && !orphanOwned) {
      return NextResponse.json({ error: "الأصل غير موجود" }, { status: 404 });
    }
    const result = await cloudinary.uploader.destroy(publicId);
    if (asset.exists) await assetRef.delete();
    return NextResponse.json({ result: result.result });
  } catch (error) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
