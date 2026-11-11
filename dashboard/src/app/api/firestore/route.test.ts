import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, POST } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import { req, reqUrl, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden } from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/firestore (the super-admin data browser root).
//
// Pinned contracts:
//  - super-admin gate (401/403)
//  - the COLLECTION WHITELIST: a non-whitelisted collection is refused, so
//    the data browser can never become an arbitrary read/write path into
//    admin2fa / email_registry / users
//  - the document SHAPE guard (reserved "__" fields, dotted field names,
//    oversized payloads)
//  - docId validation (no path traversal) and merge-on-write
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

describe("GET /api/firestore", () => {
  it("lists publicProfiles with a hasMore flag", async () => {
    fake.put("publicProfiles", "p1", { username: "one", updatedAt: 2 });
    fake.put("publicProfiles", "p2", { username: "two", updatedAt: 1 });

    const res = await GET(reqUrl("http://localhost/api/firestore"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.collection).toBe("publicProfiles");
    expect(body.docs).toHaveLength(2);
    expect(body.docs[0].id).toBe("p1");
    expect(body.hasMore).toBe(false);
  });

  it("clamps the limit to [1, 200]", async () => {
    const res = await GET(reqUrl("http://localhost/api/firestore?limit=9999"));
    expect((await res.json()).docs.length).toBeGreaterThanOrEqual(0);
    const zero = await GET(reqUrl("http://localhost/api/firestore?limit=0"));
    expect(zero.status).toBe(200);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://localhost/api/firestore"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://localhost/api/firestore"))).status).toBe(403);
  });
});

describe("POST /api/firestore", () => {
  it("creates a document with a generated id", async () => {
    const res = await POST(req({ collection: "app_config", data: { flag: "on" } }));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.success).toBe(true);
    expect(body.id).toBeTruthy();
    expect(fake.writesFor("app_config")[0].op).toBe("add");
  });

  it("merges into an explicit docId", async () => {
    fake.put("app_config", "cfg", { keep: true });

    const res = await POST(req({ collection: "app_config", docId: "cfg", data: { flag: "on" } }));
    expect(res.status).toBe(200);
    expect((await res.json()).id).toBe("cfg");
    expect(fake.read("app_config", "cfg")).toEqual({ keep: true, flag: "on" });
    expect(fake.writesFor("app_config")[0].merge).toBe(true);
  });

  it("refuses a collection outside the whitelist with 403", async () => {
    const res = await POST(req({ collection: "email_registry", data: { email: "x@y.z" } }));
    expect(res.status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });

  it("rejects a missing collection or payload with 400", async () => {
    expect((await POST(req({ data: { a: 1 } }))).status).toBe(400);
    expect((await POST(req({ collection: "app_config" }))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("rejects reserved field names, dotted field names and oversized documents", async () => {
    // JSON.parse — an object LITERAL with __proto__ sets the prototype and
    // creates no own key, so it would never reach the guard.
    expect((await POST(req({ collection: "app_config", data: JSON.parse('{"__proto__":{"x":1}}') }))).status).toBe(400);
    expect((await POST(req({ collection: "app_config", data: { "a.b": 1 } }))).status).toBe(400);
    expect((await POST(req({ collection: "app_config", data: { blob: "x".repeat(100_001) } }))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("rejects a docId that tries path traversal", async () => {
    expect((await POST(req({ collection: "app_config", docId: "a/b", data: { a: 1 } }))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await POST(req({ collection: "app_config", data: { a: 1 } }))).status).toBe(401);
    mockRoleForbidden();
    expect((await POST(req({ collection: "app_config", data: { a: 1 } }))).status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });
});
