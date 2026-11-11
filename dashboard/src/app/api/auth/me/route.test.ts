import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { mockSessionUser, mockSessionUnauthenticated } from "@/app/api/__tests__/route-harness";

// U-32 rung 4 — /api/auth/me (the dashboard session probe).
//
// Pinned contracts:
//  - 401 without a valid session cookie
//  - 403 for a VIEWER: viewers are not allowed into the dashboard, and the
//    response names the role so the client can re-route
//  - 200 for a moderator/super-admin, returning the session profile
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, getCurrentUser: vi.fn() };
});

beforeEach(() => {
  vi.clearAllMocks();
});

describe("GET /api/auth/me", () => {
  it("returns the session profile for an admin", async () => {
    mockSessionUser({ role: "moderator" });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.uid).toBe("admin-1");
    expect(body.role).toBe("moderator");
    expect(body.email).toBe("admin@example.com");
  });

  it("refuses a viewer with 403 and names the role", async () => {
    mockSessionUser({ role: "viewer" });

    const res = await GET();
    expect(res.status).toBe(403);
    const body = await res.json();
    expect(body.role).toBe("viewer");
    expect(body.error).toBeTruthy();
  });

  it("returns 401 without a valid session", async () => {
    mockSessionUnauthenticated();
    expect((await GET()).status).toBe(401);
  });
});
