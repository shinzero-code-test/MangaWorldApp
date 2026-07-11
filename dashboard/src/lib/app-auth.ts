import type { DecodedIdToken } from "firebase-admin/auth";
import { NextRequest } from "next/server";
import { getAdminAuth } from "./firebase-admin";

export async function verifyAppIdToken(request: NextRequest): Promise<DecodedIdToken> {
  const authorization = request.headers.get("authorization");
  const token = authorization?.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) throw new Error("Unauthorized");
  return getAdminAuth().verifyIdToken(token, true);
}
