import { NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { authenticator } from "otplib";
import QRCode from "qrcode";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    const user = await getCurrentUser();

    // Check if already set up
    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    if (doc.exists && doc.data()?.enabled) {
      return NextResponse.json({ alreadyEnabled: true });
    }

    // Generate or reuse pending secret
    let secret: string;
    if (doc.exists && doc.data()?.secret && !doc.data()?.enabled) {
      secret = doc.data()!.secret;
    } else {
      secret = authenticator.generateSecret();
      await getAdminDb().collection("admin2fa").doc(user.uid).set({
        secret,
        enabled: false,
        createdAt: Date.now(),
      });
    }

    const otpauth = authenticator.keyuri(
      user.email,
      "MangaWorld Admin",
      secret
    );
    const qrDataUrl = await QRCode.toDataURL(otpauth, {
      width: 280,
      margin: 2,
      color: { dark: "#ffffff", light: "#00000000" },
    });

    return NextResponse.json({ secret, qrDataUrl });
  } catch (error: any) {
    return NextResponse.json(
      { error: error.message || "خطأ" },
      { status: 401 }
    );
  }
}
