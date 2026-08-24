import { NextRequest, NextResponse } from "next/server";
import { v2 as cloudinary } from "cloudinary";
import { verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb } from "@/lib/firebase-admin";
import { cloudinaryAssetId } from "@/lib/cloudinary-assets";

cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    if (!(await allowAppMutation(`delete:${user.uid}`, 30, 60 * 60 * 1000))){
      return NextResponse.json({ error: "Too many delete requests" }, { status: 429 });
    }
    const { publicId } = await request.json();
    if (typeof publicId !== "string" || publicId.length === 0 || publicId.length > 512) {
      return NextResponse.json({ error: "A valid publicId is required" }, { status: 400 });
    }

    const assetRef = getAdminDb().collection("cloudinaryAssets").doc(cloudinaryAssetId(publicId));
    const asset = await assetRef.get();
    if (!asset.exists || asset.data()?.uid !== user.uid || asset.data()?.publicId !== publicId) {
      return NextResponse.json({ error: "Asset not found" }, { status: 404 });
    }
    const result = await cloudinary.uploader.destroy(publicId);
    await assetRef.delete();
    return NextResponse.json({ result: result.result });
  } catch (error) {
    console.error("Cloudinary app delete error:", error);
    return NextResponse.json({ error: "Delete failed" }, { status: 401 });
  }
}
