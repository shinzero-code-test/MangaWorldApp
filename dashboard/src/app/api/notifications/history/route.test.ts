import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, POST } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { req, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/notifications/history (the manual-notification audit
// trail).
//
// Pinned contracts:
//  - super-admin gate on both verbs (401/403)
//  - the stored entry is bounded (title ≤200, body ≤2000, topic ≤120,
//    targetUids ≤500 and each uid bounded)
//  - duplicate target uids are de-duplicated
//  - the entry is stored with status "manual" — never "sent", because no
//    push was dispatched for it and a fabricated sent-status corrupts the
//    audit trail
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
});

describe("GET /api/notifications/history", () => {
  it("returns the newest entries", async () => {
    fake.put("notification_history", "h1", { title: "t", body: "b", sentAt: 5 });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.history).toHaveLength(1);
    expect(body.history[0].id).toBe("h1");
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});

describe("POST /api/notifications/history", () => {
  it("stores a bounded manual entry with de-duplicated target uids", async () => {
    const res = await POST(req({ title: " t ", body: " b ", targetUids: ["u1", "u1", "u2"] }));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.success).toBe(true);
    expect(body.entry.title).toBe("t");
    expect(body.entry.body).toBe("b");
    expect(body.entry.targetUids).toEqual(["u1", "u2"]);
    expect(body.entry.status).toBe("manual");
    expect(body.entry.sentBy).toBe("admin-manual");
    expect(body.entry.sentAt).toBeGreaterThan(0);
  });

  it("rejects oversized and malformed payloads with 400 and stores nothing", async () => {
    expect((await POST(req({ title: "x".repeat(201), body: "b" }))).status).toBe(400);
    expect((await POST(req({ title: "t", body: "x".repeat(2001) }))).status).toBe(400);
    expect((await POST(req({ title: "t", body: "b", topic: "x".repeat(121) }))).status).toBe(400);
    expect((await POST(req({ title: "t", body: "b", targetUids: "u1" }))).status).toBe(400);
    expect((await POST(req({ title: "t", body: "b", targetUids: [""] }))).status).toBe(400);
    expect((await POST(req({ title: "t", body: "b", targetUids: new Array(501).fill("u") }))).status).toBe(400);
    expect((await POST(req({ title: "t" }))).status).toBe(400);
    expect((await POST(req([]))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await POST(req({ title: "t", body: "b" }))).status).toBe(401);
    mockRoleForbidden();
    expect((await POST(req({ title: "t", body: "b" }))).status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });
});
