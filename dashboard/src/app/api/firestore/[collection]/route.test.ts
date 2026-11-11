import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, POST } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  req,
  reqUrl,
  collectionParams,
  mockRoleOk,
  mockRoleUnauthorized,
  mockRoleForbidden,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/firestore/[collection] (super-admin data browser).
//
// Pinned contracts:
//  - super-admin gate
//  - the collection whitelist (400 for anything outside it)
//  - cursor pagination: an invalid startAfter is refused, a valid one
//    resolves the anchor document
//  - the document shape guard and the docId guard
//  - DA-1: an explicit-id create MERGES instead of replacing the live doc
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

describe("GET /api/firestore/[collection]", () => {
  it("returns documents with fields and a next cursor", async () => {
    fake.put("community_manga", "m1", { title: "One" });
    fake.put("community_manga", "m2", { title: "Two" });

    const res = await GET(reqUrl("http://localhost/api/firestore/community_manga"), collectionParams("community_manga"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.documents).toHaveLength(2);
    expect(body.documents[0]).toEqual({ id: "m1", fields: { title: "One" } });
    expect(body.hasMore).toBe(false);
    expect(body.nextCursor).toBeNull();
  });

  it("refuses a collection outside the whitelist with 400", async () => {
    const res = await GET(reqUrl("http://localhost/api/firestore/users"), collectionParams("users"));
    expect(res.status).toBe(400);
    expect((await res.json()).error).toBe("Collection not allowed");
  });

  it("refuses an invalid startAfter cursor with 400", async () => {
    const res = await GET(
      reqUrl("http://localhost/api/firestore/community_manga?startAfter=a%2Fb"),
      collectionParams("community_manga"),
    );
    expect(res.status).toBe(400);
    expect((await res.json()).error).toBe("Invalid startAfter");
  });

  it("accepts a valid startAfter cursor", async () => {
    fake.put("community_manga", "m1", { title: "One" });
    const res = await GET(
      reqUrl("http://localhost/api/firestore/community_manga?startAfter=m1"),
      collectionParams("community_manga"),
    );
    expect(res.status).toBe(200);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://localhost/api/firestore/app_config"), collectionParams("app_config"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://localhost/api/firestore/app_config"), collectionParams("app_config"))).status).toBe(403);
  });
});

describe("POST /api/firestore/[collection]", () => {
  it("creates a document and returns its id", async () => {
    const res = await POST(req({ data: { title: "New" } }), collectionParams("community_manga"));
    expect(res.status).toBe(200);
    expect((await res.json()).id).toBeTruthy();
    expect(fake.writesFor("community_manga")[0].op).toBe("add");
  });

  it("merges into an explicit id instead of replacing the doc (DA-1)", async () => {
    fake.put("user_achievements", "uid-1", { points: 10 });

    const res = await POST(req({ id: "uid-1", data: { points: 25 } }), collectionParams("user_achievements"));
    expect(res.status).toBe(200);
    expect(fake.read("user_achievements", "uid-1")).toEqual({ points: 25 });
    expect(fake.writesFor("user_achievements")[0].merge).toBe(true);
  });

  it("refuses a collection outside the whitelist with 400", async () => {
    const res = await POST(req({ data: { a: 1 } }), collectionParams("admin2fa"));
    expect(res.status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("rejects a malformed document and an invalid id with 400", async () => {
    expect((await POST(req({ data: { "__x": 1 } }), collectionParams("app_config"))).status).toBe(400);
    expect((await POST(req({ id: "a/b", data: { a: 1 } }), collectionParams("app_config"))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await POST(req({ data: { a: 1 } }), collectionParams("app_config"))).status).toBe(401);
    mockRoleForbidden();
    expect((await POST(req({ data: { a: 1 } }), collectionParams("app_config"))).status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });
});
