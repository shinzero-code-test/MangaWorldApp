import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { POST } from "./route";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { isTextAllowed } from "@/lib/moderation";

// First dashboard API-route suite (U-19): the Admin SDK is fully faked so
// no emulator or credentials are needed. Firestore transactions are faked
// to capture the exact set/update ops and prove the reply create and the
// root replyCount increment commit inside one transaction.
vi.mock("@/lib/app-auth", () => ({
  verifyAppIdToken: vi.fn(),
  rejectAnonymousUser: vi.fn(),
}));
vi.mock("@/lib/app-rate-limit", () => ({
  allowAppMutation: vi.fn(),
}));
vi.mock("@/lib/moderation", () => ({
  isTextAllowed: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
  getAdminMessaging: vi.fn(),
}));
vi.mock("firebase-admin/firestore", () => ({
  FieldValue: {
    increment: (n: number) => ({ __increment: n }),
  },
}));

type Ref = { path: string; id: string };
type TxOp = { kind: "set" | "update"; ref: Ref; data: Record<string, unknown> };

function ref(path: string): Ref {
  return { path, id: path.split("/").pop() ?? path };
}

function snapshot(data: Record<string, unknown> | undefined) {
  return {
    exists: data !== undefined,
    data: () => data,
    id: "fake",
  };
}

/** Minimal fake Firestore: collection chains + transaction capture. */
function fakeDb(opts: {
  parentDoc?: Record<string, unknown> | undefined;
  reviewDoc?: Record<string, unknown> | undefined;
  profileDoc?: Record<string, unknown> | undefined;
  devicesDocs?: Array<{ token: string }>;
}) {
  const txOps: TxOp[] = [];

  function makeCollection(parentPath: string) {
    return {
      doc: (id: string) => {
        const docPath = `${parentPath}/${id}`;
        const docRef: Ref & {
          get: () => Promise<unknown>;
          set: (data: Record<string, unknown>) => Promise<void>;
          collection: (name: string) => unknown;
        } = {
          ...ref(docPath),
          get: async () => {
            // Direct reads (profile) go through ref.get(); root reads go
            // through the transaction fake below.
            if (docPath === "publicProfiles/uid-1") return snapshot(opts.profileDoc);
            return snapshot(undefined);
          },
          set: async (_data: Record<string, unknown>) => {
            void _data;
          },
          collection: (name: string) => makeCollection(docPath + `/${name}`),
        };
        return docRef;
      },
    };
  }

  const db = {
    collection: (name: string) => {
      if (name === "publicProfiles") return makeCollection(name);
      if (name === "usernames") {
        return {
          doc: (_id: string) => ({
            ...ref(`${name}/${_id}`),
            get: async () => snapshot(undefined),
          }),
        };
      }
      if (name === "users") {
        return {
          doc: (_id: string) => ({
            ...ref(`${name}/${_id}`),
            collection: (sub: string) => {
              if (sub === "devices") {
                return {
                  get: async () => ({
                    docs: (opts.devicesDocs ?? []).map((d) => ({ data: () => d })),
                  }),
                };
              }
              return {
                doc: (_id2: string) => ({
                  ...ref(`${name}/${_id}/${sub}/${_id2}`),
                  id: _id2,
                  set: async (_data: Record<string, unknown>) => {
                    void _data;
                  },
                }),
              };
            },
          }),
        };
      }
      // community_manga tree: build path-aware refs.
      return {
        doc: (mangaId: string) => {
          const mangaPath = `community_manga/${mangaId}`;
          return {
            ...ref(mangaPath),
            collection: (sub: string) => {
              if (sub === "comments") return makeCollection(mangaPath + "/comments");
              if (sub === "reviews") return makeCollection(mangaPath + "/reviews");
              if (sub === "chapters") {
                return {
                  doc: (chapterKey: string) => ({
                    ...ref(`${mangaPath}/chapters/${chapterKey}`),
                    collection: (name2: string) => makeCollection(`${mangaPath}/chapters/${chapterKey}/${name2}`),
                  }),
                };
              }
              return makeCollection(mangaPath + `/${sub}`);
            },
          };
        },
      };
    },
    runTransaction: async (fn: (tx: unknown) => Promise<unknown>) => {
      const tx = {
        get: async (r: Ref) => {
          if (r.path.endsWith("/comments/root1") || r.path.endsWith("/comments/root-comment")) {
            return snapshot(opts.parentDoc);
          }
          if (r.path.endsWith("/reviews/rev1")) return snapshot(opts.reviewDoc);
          return snapshot(undefined);
        },
        set: (r: Ref, data: Record<string, unknown>) => {
          txOps.push({ kind: "set", ref: r, data });
        },
        update: (r: Ref, data: Record<string, unknown>) => {
          txOps.push({ kind: "update", ref: r, data });
        },
      };
      return await fn(tx);
    },
  };

  return { db, txOps };
}

function req(payload: unknown): NextRequest {
  return { json: async () => payload } as unknown as NextRequest;
}

const ROOT_COMMENT = {
  id: "root1",
  mangaId: "m1",
  authorUid: "uid-root",
  authorUsername: "rootuser",
  parentId: null,
  reviewId: null,
  isDeleted: false,
};

const ROOT_REVIEW = {
  id: "rev1",
  mangaId: "m1",
  authorUid: "uid-root",
  isDeleted: false,
};

const PROFILE = {
  displayName: "Reply Author",
  username: "replier",
  avatarUrl: "https://example/avatar.png",
  badgeLabel: "Avid Reader",
};

function mockAuthed() {
  vi.mocked(verifyAppIdToken).mockResolvedValue({
    uid: "uid-1",
    firebase: { sign_in_provider: "password" },
  } as never);
  vi.mocked(rejectAnonymousUser).mockImplementation(() => {});
  vi.mocked(allowAppMutation).mockResolvedValue(true);
  vi.mocked(isTextAllowed).mockResolvedValue(true);
}

beforeEach(() => {
  vi.clearAllMocks();
  mockAuthed();
});

describe("POST /api/community/replies", () => {
  it("creates a comment reply and increments root replyCount in one transaction", async () => {
    const { db, txOps } = fakeDb({ parentDoc: ROOT_COMMENT, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    vi.mocked(getAdminMessaging).mockReturnValue({
      sendEachForMulticast: vi.fn().mockResolvedValue({ successCount: 0 }),
    } as never);

    const res = await POST(req({
      mangaId: "m1",
      chapterUrl: "https://example/ch/1",
      slug: "test-slug",
      sourceId: "azora",
      text: "great chapter!",
      spoiler: false,
      parentId: "root1",
    }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { success: boolean; id: string };
    expect(body.success).toBe(true);
    expect(body.id).toBeTruthy();

    const setOp = txOps.find((op) => op.kind === "set");
    const updateOp = txOps.find((op) => op.kind === "update");
    expect(setOp).toBeDefined();
    expect(updateOp).toBeDefined();
    // Both ops belong to the same transaction invocation (both captured).
    expect(updateOp!.ref.path).toContain("/comments/root1");
    expect((updateOp!.data as { replyCount: unknown }).replyCount).toEqual({ __increment: 1 });

    const reply = setOp!.data;
    expect(reply.parentId).toBe("root1");
    expect(reply.threadRootId).toBe("root1");
    expect(reply.reviewId).toBeNull();
    expect(reply.authorUid).toBe("uid-1");
    // Server-derived snapshot (payload sent no author fields).
    expect(reply.authorName).toBe("Reply Author");
    expect(reply.authorUsername).toBe("replier");
    // Addressee derived from root (no mentions in text).
    expect(reply.replyToUid).toBe("uid-root");
    expect(reply.replyToUsername).toBe("rootuser");
    expect(reply.replyCount).toBe(0);
    expect(reply.isDeleted).toBe(false);
  });

  it("creates a review reply and increments the review in one transaction", async () => {
    const { db, txOps } = fakeDb({ reviewDoc: ROOT_REVIEW, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    vi.mocked(getAdminMessaging).mockReturnValue({
      sendEachForMulticast: vi.fn().mockResolvedValue({ successCount: 0 }),
    } as never);

    const res = await POST(req({
      mangaId: "m1",
      slug: "test-slug",
      sourceId: "azora",
      text: "agree with this review",
      reviewId: "rev1",
    }));
    expect(res.status).toBe(200);
    const setOp = txOps.find((op) => op.kind === "set");
    const updateOp = txOps.find((op) => op.kind === "update");
    expect(setOp).toBeDefined();
    expect(updateOp).toBeDefined();
    expect(updateOp!.ref.path).toContain("/reviews/rev1");
    expect((updateOp!.data as { replyCount: unknown }).replyCount).toEqual({ __increment: 1 });
    expect(setOp!.data.reviewId).toBe("rev1");
    expect(setOp!.data.parentId).toBeNull();
    expect(setOp!.data.threadRootId).toBeNull();
  });

  it("rejects unauthenticated callers with 401", async () => {
    vi.mocked(verifyAppIdToken).mockRejectedValue(new Error("Unauthorized"));
    const res = await POST(req({ mangaId: "m1", text: "hi", parentId: "root1" }));
    expect(res.status).toBe(401);
  });

  it("rejects anonymous users with 403", async () => {
    vi.mocked(verifyAppIdToken).mockResolvedValue({
      uid: "anon-1",
      firebase: { sign_in_provider: "anonymous" },
    } as never);
    vi.mocked(rejectAnonymousUser).mockImplementation(() => {
      throw new Error("Forbidden");
    });
    const res = await POST(req({ mangaId: "m1", text: "hi", parentId: "root1" }));
    expect(res.status).toBe(403);
  });

  it("rate-limits with 429", async () => {
    vi.mocked(allowAppMutation).mockResolvedValue(false);
    const res = await POST(req({ mangaId: "m1", text: "hi", parentId: "root1" }));
    expect(res.status).toBe(429);
  });

  it("rejects empty text with 400 and never opens a transaction", async () => {
    const { db, txOps } = fakeDb({ parentDoc: ROOT_COMMENT, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ mangaId: "m1", text: "   ", parentId: "root1" }));
    expect(res.status).toBe(400);
    expect(txOps).toHaveLength(0);
  });

  it("rejects replies with no target (both parentId and reviewId missing) with 400", async () => {
    const { db, txOps } = fakeDb({ parentDoc: ROOT_COMMENT, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ mangaId: "m1", text: "hi" }));
    expect(res.status).toBe(400);
    expect(txOps).toHaveLength(0);
  });

  it("rejects replies with both targets present with 400", async () => {
    const { db, txOps } = fakeDb({ parentDoc: ROOT_COMMENT, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ mangaId: "m1", text: "hi", parentId: "root1", reviewId: "rev1" }));
    expect(res.status).toBe(400);
    expect(txOps).toHaveLength(0);
  });

  it("rejects a nested parent (not top-level) with 400", async () => {
    const nestedParent = { ...ROOT_COMMENT, id: "root1", parentId: "some-root" };
    const { db, txOps } = fakeDb({ parentDoc: nestedParent, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ mangaId: "m1", text: "hi", parentId: "root1" }));
    expect(res.status).toBe(400);
    // The reply set must not have been staged before the flat-thread check.
    expect(txOps.find((op) => op.kind === "set")).toBeUndefined();
  });

  it("returns 404 when the parent does not exist", async () => {
    const { db } = fakeDb({ parentDoc: undefined, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ mangaId: "m1", text: "hi", parentId: "root1" }));
    expect(res.status).toBe(404);
  });

  it("rejects banned text with 400 before writing", async () => {
    const { db, txOps } = fakeDb({ parentDoc: ROOT_COMMENT, profileDoc: PROFILE });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    vi.mocked(isTextAllowed).mockResolvedValue(false);
    const res = await POST(req({ mangaId: "m1", text: "banned word", parentId: "root1" }));
    expect(res.status).toBe(400);
    expect(txOps).toHaveLength(0);
  });

  it("keeps the committed count when the notification fan-out throws", async () => {
    const { db, txOps } = fakeDb({
      parentDoc: ROOT_COMMENT,
      profileDoc: PROFILE,
      devicesDocs: [{ token: "tok" }],
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    vi.mocked(getAdminMessaging).mockReturnValue({
      sendEachForMulticast: vi.fn().mockRejectedValue(new Error("fcm down")),
    } as never);

    const res = await POST(req({
      mangaId: "m1",
      slug: "test-slug",
      sourceId: "azora",
      text: "hello root",
      parentId: "root1",
    }));
    expect(res.status).toBe(200);
    expect(txOps.find((op) => op.kind === "set")).toBeDefined();
    const updateOp = txOps.find((op) => op.kind === "update");
    expect(updateOp).toBeDefined();
    expect((updateOp!.data as { replyCount: unknown }).replyCount).toEqual({ __increment: 1 });
  });
});
