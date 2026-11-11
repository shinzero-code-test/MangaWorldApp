import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { allowAppMutation } from "@/lib/app-rate-limit";
import {
  req,
  mockAppAuthed,
  mockAppAnon,
  mockAppUnauthorized,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb, fakeMessaging } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/notifications/follow (app-facing follow fan-out).
//
// Pinned contracts:
//  - app-user gate: 401 without a valid ID token, 403 for an anonymous
//    session (guests must not mint notifications)
//  - the per-user rate limit (429)
//  - the FOLLOW RELATIONSHIP MUST EXIST server-side (a forged notify is
//    refused with 404 — the client cannot invent a following edge)
//  - no self-follow
//  - the notification document uses the deterministic `follow_{followerUid}`
//    id (repeat follows upsert instead of spamming)
vi.mock("@/lib/app-auth", () => ({
  verifyAppIdToken: vi.fn(),
  rejectAnonymousUser: vi.fn(),
}));
vi.mock("@/lib/app-rate-limit", () => ({
  allowAppMutation: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
  getAdminMessaging: vi.fn(),
}));

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockAppAuthed();
  vi.mocked(allowAppMutation).mockResolvedValue(true);
});

describe("POST /api/notifications/follow", () => {
  it("writes the FOLLOW notification and pushes to the target's devices", async () => {
    fake.put("relationships/uid-1/following", "target-1", { followedAt: 1 });
    fake.put("publicProfiles", "uid-1", { displayName: "Alice" });
    fake.put("users/target-1/devices", "d1", { token: "device-token" });
    const { messaging, sends } = fakeMessaging();
    vi.mocked(getAdminMessaging).mockReturnValue(messaging as never);

    const res = await POST(req({ targetUid: "target-1" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });

    const stored = fake.read("users/target-1/notifications", "follow_uid-1");
    expect(stored?.type).toBe("FOLLOW");
    expect(stored?.targetUid).toBe("uid-1");
    expect(stored?.body).toContain("Alice");
    expect(sends).toHaveLength(1);
    expect(sends[0].data.type).toBe("FOLLOW");
  });

  it("refuses a forged follow (the relationship does not exist)", async () => {
    const res = await POST(req({ targetUid: "target-1" }));
    expect(res.status).toBe(404);
    expect(fake.writes).toHaveLength(0);
  });

  it("rejects a self-follow and malformed targets with 400", async () => {
    expect((await POST(req({ targetUid: "uid-1" }))).status).toBe(400);
    expect((await POST(req({ targetUid: "" }))).status).toBe(400);
    expect((await POST(req({ targetUid: "a/b" }))).status).toBe(400);
    expect((await POST(req({ targetUid: 42 }))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 429 when the caller is rate limited", async () => {
    vi.mocked(allowAppMutation).mockResolvedValue(false);
    const res = await POST(req({ targetUid: "target-1" }));
    expect(res.status).toBe(429);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401 without a valid ID token and 403 for anonymous users", async () => {
    mockAppUnauthorized();
    expect((await POST(req({ targetUid: "target-1" }))).status).toBe(401);
    mockAppAnon();
    expect((await POST(req({ targetUid: "target-1" }))).status).toBe(403);
  });
});
