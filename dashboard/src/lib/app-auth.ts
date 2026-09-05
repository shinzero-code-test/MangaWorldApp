import type { DecodedIdToken } from "firebase-admin/auth";
import { NextRequest } from "next/server";
import { getAdminAuth } from "./firebase-admin";

export async function verifyAppIdToken(request: NextRequest): Promise<DecodedIdToken> {
  const authorization = request.headers.get("authorization");
  const token = authorization?.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) throw new Error("Unauthorized");
  return getAdminAuth().verifyIdToken(token, true);
}

/**
 * Guest writes are client-gated in the app, but the API must enforce it too:
 * anonymous Firebase sessions (free to mint) must not vote, notify, upload,
 * or delete. Throws "Forbidden" for anonymous callers (mapped to 403 by
 * genericErrorResponse).
 */
export function rejectAnonymousUser(user: DecodedIdToken): void {
  const provider = (user.firebase as { sign_in_provider?: string } | undefined)?.sign_in_provider;
  if (provider === "anonymous") throw new Error("Forbidden");
}
