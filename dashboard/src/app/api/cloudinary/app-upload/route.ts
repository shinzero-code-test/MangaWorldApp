import { randomUUID } from "crypto";
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

const ASSET_FOLDERS = {
  avatar: "avatars",
  banner: "banners",
  "list-cover": "list-covers",
} as const;
type AssetType = keyof typeof ASSET_FOLDERS;
const IMAGE_PATTERN = /^data:image\/(jpeg|png|webp);base64,([A-Za-z0-9+/]+={0,2})$/;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

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
    const clientKey = `${user.uid}:${request.headers.get("x-forwarded-for")?.split(",")[0] ?? "unknown"}`;
    if (!(await allowAppMutation(clientKey, 20, 60 * 60 * 1000))){
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }

    const { image, assetType } = await request.json();
    if (typeof image !== "string" || !isAssetType(assetType)) {
      return NextResponse.json({ error: "صورة ونوع أصل صالحين مطلوبان" }, { status: 400 });
    }
    // Pre-cap the raw string BEFORE base64 decoding: without this a 50–100 MB
    // JSON body is decoded in a serverless function before the byte check runs.
    if (image.length > 8_000_000) {
      return NextResponse.json({ error: "الصورة كبيرة جداً" }, { status: 400 });
    }
    const match = image.match(IMAGE_PATTERN);
    if (!match) return NextResponse.json({ error: "صيغة الصورة غير مدعومة" }, { status: 400 });
    const bytes = Buffer.from(match[2], "base64");
    if (bytes.length === 0 || bytes.length > MAX_IMAGE_BYTES) {
      return NextResponse.json({ error: "الصورة كبيرة جداً" }, { status: 400 });
    }

    const result = await cloudinary.uploader.upload(image, {
      folder: `app/${user.uid}/${ASSET_FOLDERS[assetType]}`,
      public_id: randomUUID(),
      resource_type: "image",
      transformation: [{ width: 800, height: 800, crop: "limit" }, { quality: "auto:good" }],
    });
    await getAdminDb().collection("cloudinaryAssets").doc(cloudinaryAssetId(result.public_id)).set({
      uid: user.uid,
      publicId: result.public_id,
      assetType,
      url: result.secure_url,
      createdAt: Date.now(),
    });

    return NextResponse.json({ url: result.secure_url, publicId: result.public_id, width: result.width, height: result.height });
  } catch (error) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

function isAssetType(value: unknown): value is AssetType {
  return typeof value === "string" && value in ASSET_FOLDERS;
}
