import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PUT, DELETE } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  req,
  reqUrl,
  docParams,
  mockRoleOk,
  mockRoleUnauthorized,
  mockRoleForbidden,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/firestore/[collection]/[docId] (super-admin document
// read/write/delete).
//
// Pinned contracts:
//  - super-admin gate on every verb
//  - the collection whitelist AND the docId guard on every verb (a docId
//    with a slash must never reach Firestore)
//  - 404 for a missing document
//  - PUT merges (it must not blank a live doc) and rejects a malformed body
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

describe("GET /api/firestore/[collection]/[docId]", () => {
  it("returns the document fields", async () => {
    fake.put("app_config", "cfg", { flag: "on" });

    const res = await GET(reqUrl("http://localhost/api/firestore/app_config/cfg"), docParams("app_config", "cfg"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ id: "cfg", fields: { flag: "on" } });
  });

  it("returns 404 when the document does not exist", async () => {
    const res = await GET(reqUrl("http://localhost/api/firestore/app_config/nope"), docParams("app_config", "nope"));
    expect(res.status).toBe(404);
  });

  it("refuses a collection outside the whitelist and an invalid docId with 400", async () => {
    expect((await GET(reqUrl("http://x/"), docParams("admin2fa", "uid"))).status).toBe(400);
    expect((await GET(reqUrl("http://x/"), docParams("app_config", "a/b"))).status).toBe(400);
    expect((await GET(reqUrl("http://x/"), docParams("app_config", ""))).status).toBe(400);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://x/"), docParams("app_config", "cfg"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://x/"), docParams("app_config", "cfg"))).status).toBe(403);
  });
});

describe("PUT /api/firestore/[collection]/[docId]", () => {
  it("merges the payload into the document", async () => {
    fake.put("app_config", "cfg", { keep: true });

    const res = await PUT(req({ flag: "off" }), docParams("app_config", "cfg"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });
    expect(fake.read("app_config", "cfg")).toEqual({ keep: true, flag: "off" });
    expect(fake.writesFor("app_config")[0].merge).toBe(true);
  });

  it("rejects a malformed payload with 400 and writes nothing", async () => {
    // JSON.parse — an object LITERAL with __proto__ sets the prototype and
    // creates no own key, so it would never reach the guard.
    expect((await PUT(req(JSON.parse('{"__proto__":{"x":1}}')), docParams("app_config", "cfg"))).status).toBe(400);
    expect((await PUT(req({ "a.b": 1 }), docParams("app_config", "cfg"))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("refuses a collection outside the whitelist and an invalid docId with 400", async () => {
    expect((await PUT(req({ a: 1 }), docParams("users", "uid-1"))).status).toBe(400);
    expect((await PUT(req({ a: 1 }), docParams("app_config", "a/b"))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await PUT(req({ a: 1 }), docParams("app_config", "cfg"))).status).toBe(401);
    mockRoleForbidden();
    expect((await PUT(req({ a: 1 }), docParams("app_config", "cfg"))).status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });
});

describe("DELETE /api/firestore/[collection]/[docId]", () => {
  it("deletes the document", async () => {
    fake.put("app_config", "cfg", { flag: "on" });

    const res = await DELETE(reqUrl("http://x/"), docParams("app_config", "cfg"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });
    expect(fake.read("app_config", "cfg")).toBeUndefined();
    expect(fake.writesFor("app_config")[0].op).toBe("delete");
  });

  it("refuses a collection outside the whitelist and an invalid docId with 400", async () => {
    expect((await DELETE(reqUrl("http://x/"), docParams("users", "uid-1"))).status).toBe(400);
    expect((await DELETE(reqUrl("http://x/"), docParams("app_config", "a/b"))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await DELETE(reqUrl("http://x/"), docParams("app_config", "cfg"))).status).toBe(401);
    mockRoleForbidden();
    expect((await DELETE(reqUrl("http://x/"), docParams("app_config", "cfg"))).status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });
});
