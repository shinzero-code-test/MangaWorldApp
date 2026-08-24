import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { v2 as cloudinary } from "cloudinary";
import { isValidFolder } from "@/lib/validate";
import { consumeRateLimit, genericErrorResponse } from "@/lib/security";

// Configure Cloudinary — API keys come from .env.local
cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

export const dynamic = 'force-dynamic';

/**
 * POST /api/cloudinary/upload
 *
 * Upload an image to Cloudinary via the dashboard backend.
 * Body: { "image": "data:image/...;base64,...", "folder": "avatars" }
 *
 * Hardening:
 *  - data-URI inputs only. Remote-URL fetching is disabled so Cloudinary's
 *    infrastructure cannot be used as an SSRF-by-proxy.
 *  - folder values must match a bounded relative-path pattern (no traversal).
 *  - per-uid rate limit (30 uploads / 5 min) on this mutation endpoint.
 */
const ALLOWED_MIME = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]);
const DATA_URI_RE = /^data:(image\/(?:jpeg|png|webp|gif));base64,[A-Za-z0-9+/=\s]+$/;

export async function POST(request: NextRequest) {
  try {
    const user = await requireRole("super-admin");

    const limiter = await consumeRateLimit("cloudinary-upload", user.uid, 30, 5 * 60 * 1000);
    if (!limiter.allowed) {
      return NextResponse.json({ error: "Too many uploads — try later" }, { status: 429 });
    }

    const body = await request.json();
    const image: unknown = body?.image;
    const folder: string = typeof body?.folder === "string" && body.folder ? body.folder : "uploads";

    if (!isValidFolder(folder)) {
      return NextResponse.json({ error: "Invalid folder" }, { status: 400 });
    }
    if (typeof image !== "string" || image.length === 0 || image.length > 8_000_000) {
      return NextResponse.json({ error: "image is required (max ~6MB payload)" }, { status: 400 });
    }

    const mimeMatch = DATA_URI_RE.exec(image);
    if (!mimeMatch || !ALLOWED_MIME.has(mimeMatch[1])) {
      return NextResponse.json(
        { error: "Only base64 data URIs of type jpeg/png/webp/gif are accepted" },
        { status: 400 }
      );
    }

    // Upload to Cloudinary
    const result = await cloudinary.uploader.upload(image, {
      folder,
      resource_type: "image",
      transformation: [
        { width: 800, height: 800, crop: "limit" },
        { quality: "auto:good" },
      ],
    });

    return NextResponse.json({
      url: result.secure_url,
      publicId: result.public_id,
      width: result.width,
      height: result.height,
    });
  } catch (error) {
    const { body, status } = genericErrorResponse(error, "Upload failed");
    return NextResponse.json(body, { status });
  }
}

/**
 * DELETE /api/cloudinary/upload?publicId=...
 *
 * Delete an image from Cloudinary.
 */
export async function DELETE(request: NextRequest) {
  try {
    await requireRole("super-admin");

    const { searchParams } = new URL(request.url);
    const publicId = searchParams.get("publicId");

    if (!publicId || !/^[a-zA-Z0-9_\-\/]{1,200}$/.test(publicId)) {
      return NextResponse.json({ error: "publicId is required" }, { status: 400 });
    }

    const result = await cloudinary.uploader.destroy(publicId);
    return NextResponse.json({ result });
  } catch (error) {
    const { body, status } = genericErrorResponse(error, "Delete failed");
    return NextResponse.json(body, { status });
  }
}
