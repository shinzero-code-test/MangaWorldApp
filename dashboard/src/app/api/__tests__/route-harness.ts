import { vi } from "vitest";
import type { NextRequest } from "next/server";
import { verifyAppIdToken, rejectAnonymousUser } from "@/lib/app-auth";
import { getCurrentUser, requireRole } from "@/lib/auth";

/**
 * Shared harness for the U-32 dashboard route matrix: mock-ID-token /
 * session-user helpers plus the request builders. Every suite still needs
 * its own `vi.mock("@/lib/...")` declarations at the TOP of the file (vitest
 * hoisting); these helpers only CONFIGURE the already-mocked modules.
 *
 * Status contract they prove (the route must enforce it, not the mock):
 *  - 401 when no valid credential is presented
 *  - 403 when a valid credential may not perform the action (anonymous
 *    app user, viewer role, stale fresh-auth, MFA grant missing)
 *  - 200 on the happy path
 * The mocks reproduce the REAL helpers' control flow (throwing "Unauthorized" /
 * "Forbidden" / "MFA verification required"), and suites keep
 * `genericErrorResponse` REAL so the 401/403 mapping itself is under test.
 */

/** Minimal NextRequest stand-in: routes only need `json()` and `headers`. */
export function req(payload: unknown): NextRequest {
  return { json: async () => payload, headers: new Headers() } as unknown as NextRequest;
}

/** Route param helper for `[uid]`-shaped routes. */
export function uidParams(uid: string) {
  return { params: Promise.resolve({ uid }) };
}

export const APP_USER = {
  uid: "uid-1",
  firebase: { sign_in_provider: "password" },
} as Record<string, unknown>;

export const APP_ANON = {
  uid: "anon-1",
  firebase: { sign_in_provider: "anonymous" },
} as Record<string, unknown>;

export function mockAppAuthed() {
  vi.mocked(verifyAppIdToken).mockResolvedValue(APP_USER as unknown as import("firebase-admin/auth").DecodedIdToken);
  vi.mocked(rejectAnonymousUser).mockImplementation(() => {});
}

export function mockAppAnon() {
  vi.mocked(verifyAppIdToken).mockResolvedValue(APP_ANON as unknown as import("firebase-admin/auth").DecodedIdToken);
  vi.mocked(rejectAnonymousUser).mockImplementation(() => {
    throw new Error("Forbidden");
  });
}

export function mockAppUnauthorized() {
  vi.mocked(verifyAppIdToken).mockRejectedValue(new Error("Unauthorized"));
}

export const SESSION_USER: Record<string, unknown> = {
  uid: "admin-1",
  email: "admin@example.com",
  role: "super-admin",
  mfaVerified: true,
};

/**
 * Mock implementations REPLACE (never queue), so each call sets what the
 * next invocation returns. (Previously `mockResolvedValue` calls from
 * `mockSessionUser` in beforeEach stacked with those from the test body,
 * and the FIRST-in-queue value — a fresh session — won, making
 * `mockSessionStale` a no-op (returns fresh authTime → route 200s.)
 */
export function mockSessionUser(
  overrides: Record<string, unknown> = {},
  authTime: number = Date.now()
) {
  vi.mocked(getCurrentUser).mockImplementation(
    async () =>
      ({
        ...SESSION_USER,
        authTime,
        ...overrides,
      }) as never
  );
}

export function mockSessionStale() {
  // >15 min old authentication: fresh-auth gates must refuse with 403.
  mockSessionUser({}, Date.now() - 16 * 60 * 1000);
}

export function mockSessionUnauthenticated() {
  vi.mocked(getCurrentUser).mockRejectedValue(new Error("Unauthorized"));
}

export function mockRoleOk() {
  vi.mocked(requireRole).mockResolvedValue({
    uid: "admin-1",
    email: "admin@example.com",
    role: "super-admin",
    mfaVerified: true,
  } as never);
}

export function mockRoleUnauthorized() {
  vi.mocked(requireRole).mockRejectedValue(new Error("Unauthorized"));
}

export function mockRoleForbidden() {
  vi.mocked(requireRole).mockRejectedValue(new Error("Forbidden"));
}

export function mockRoleMfaRequired() {
  vi.mocked(requireRole).mockRejectedValue(new Error("MFA verification required"));
}
