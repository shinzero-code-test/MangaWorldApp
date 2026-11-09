import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { req, mockAppAuthed, mockAppAnon, mockAppUnauthorized } from "@/app/api/__tests__/route-harness";

// (4) community/vote — app-token auth (401/403) → rate limit (429) →
// strict payload validation (400) → transactional counter transition
// (computeVoteTransition is REAL pure math) → best-effort author fan-out
// that must never fail the vote. Self-vote 403, missing content 404,
// ambiguous legacy ids 409.
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

type Ref = { path: string; id: string };

function ref(path: string): Ref {
  return { path, id: path.split("/").pop() ?? path };
}

function snapshot(data: Record<string, unknown> | undefined) {
  return { exists: data !== undefined, data: () => data, id: "fake" };
}

/**
 * Minimal fake Firestore for the vote route: collectionGroup search for
 * comments, direct refs for reviews, transaction capture for the vote
 * transition, and the fan-out reads/writes.
 */
function fakeDb(opts: {
  commentDocs?: Array<{ data: Record<string, unknown>; path: string }>;
  reviewData?: Record<string, unknown> | undefined;
  priorVote?: Record<string, unknown> | undefined;
  voterName?: string;
  deviceTokens?: string[];
}) {
  const txOps: Array<{ kind: "set" | "update" | "delete"; ref: Ref; data?: Record<string, unknown> }> = [];
  const notificationWrites: Array<{ ref: Ref; data: Record<string, unknown> }> = [];

  function docRef(path: string): Ref & {
    get: () => Promise<unknown>;
    collection: (name: string) => unknown;
  } {
    const r: Ref = ref(path);
    return {
      ...r,
      get: async () => {
        if (path === "publicProfiles/uid-1") {
          return snapshot(opts.voterName ? { displayName: opts.voterName } : undefined);
        }
        if (path.endsWith("/reviews/rev1")) return snapshot(opts.reviewData);
        const commentDoc = (opts.commentDocs ?? []).find((d) => d.path === path);
        if (commentDoc) return snapshot(commentDoc.data);
        return snapshot(undefined);
      },
      collection: (name: string) => ({
        doc: (id: string) => {
          const subPath = `${path}/${name}/${id}`;
          return {
            ...ref(subPath),
            id,
            get: async () => snapshot(undefined),
            set: async (data: Record<string, unknown>) => {
              notificationWrites.push({ ref: ref(subPath), data });
            },
          };
        },
      }),
    } as Ref & {
      get: () => Promise<unknown>;
      collection: (name: string) => unknown;
    };
  }

  const db = {
    collection: (name: string) => ({
      doc: (id: string) => docRef(`${name}/${id}`),
    }),
    collectionGroup: (_name: string) => ({
      where: () => ({
        limit: () => ({
          get: async () => ({
            empty: (opts.commentDocs ?? []).length === 0,
            docs: (opts.commentDocs ?? []).map((d) => ({
              data: () => d.data,
              ref: docRef(d.path),
            })),
          }),
        }),
      }),
    }),
    runTransaction: async (fn: (tx: unknown) => Promise<unknown>) => {
      const tx = {
        get: async (r: Ref) => {
          if (r.path.endsWith("/votes/uid-1")) return snapshot(opts.priorVote);
          if (r.path.endsWith("/reviews/rev1")) return snapshot(opts.reviewData);
          const commentDoc = (opts.commentDocs ?? []).find((d) => d.path === r.path);
          if (commentDoc) return snapshot(commentDoc.data);
          return snapshot(undefined);
        },
        set: (r: Ref, data: Record<string, unknown>) => {
          txOps.push({ kind: "set", ref: r, data });
        },
        update: (r: Ref, data: Record<string, unknown>) => {
          txOps.push({ kind: "update", ref: r, data });
        },
        delete: (r: Ref) => {
          txOps.push({ kind: "delete", ref: r });
        },
      };
      return await fn(tx);
    },
  };

  // Users doc: devices (fan-out reads) + notifications (fan-out writes).
  const baseCollection = db.collection;
  (db as { collection: unknown }).collection = (name: string) => {
    if (name === "users") {
      return {
        doc: (_id: string) => ({
          collection: (sub: string) => {
            if (sub === "devices") {
              return {
                get: async () => ({
                  docs: (opts.deviceTokens ?? []).map((token) => ({ data: () => ({ token }) })),
                }),
              };
            }
            return {
              doc: (id2: string) => ({
                ...ref(`users/${_id}/${sub}/${id2}`),
                id: id2,
                set: async (data: Record<string, unknown>) => {
                  notificationWrites.push({ ref: ref(`users/${_id}/${sub}/${id2}`), data });
                },
              }),
            };
          },
        }),
      };
    }
    return baseCollection(name);
  };

  return { db, txOps, notificationWrites };
}

const COMMENT_PATH = "community_manga/m1/comments/root1";
const COMMENT = {
  id: "root1",
  mangaId: "m1",
  authorUid: "uid-other",
  likes: 0,
  dislikes: 0,
  slug: "test-slug",
  sourceId: "azora",
  chapterUrl: "https://example/ch/1",
};

const REVIEW = {
  id: "rev1",
  mangaId: "m1",
  authorUid: "uid-other",
  likes: 2,
  dislikes: 0,
  slug: "test-slug",
  sourceId: "azora",
  chapterUrl: null,
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAppAuthed();
  vi.mocked(allowAppMutation).mockResolvedValue(true);
  vi.mocked(getAdminMessaging).mockReturnValue({
    sendEachForMulticast: vi.fn().mockResolvedValue({ successCount: 1 }),
  } as never);
});

describe("POST /api/community/vote", () => {
  it("casts a fresh comment like inside one transaction", async () => {
    const { db, txOps } = fakeDb({ commentDocs: [{ data: COMMENT, path: COMMENT_PATH }] });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetId: "root1", vote: 1, mangaId: "m1" }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { success: boolean; likes: number; dislikes: number; action: string; myVote: number | null };
    expect(body).toMatchObject({ success: true, likes: 1, dislikes: 0, action: "added", myVote: 1 });

    const setOp = txOps.find((op) => op.kind === "set");
    const updateOp = txOps.find((op) => op.kind === "update");
    expect(setOp).toBeDefined();
    expect(setOp!.ref.path).toContain("/votes/uid-1");
    expect(setOp!.data).toMatchObject({ uid: "uid-1", value: 1 });
    expect(updateOp!.data).toMatchObject({ likes: 1, dislikes: 0 });
  });

  it("retracts on a repeat vote (toggle) and reports myVote null", async () => {
    const { db, txOps } = fakeDb({
      commentDocs: [{ data: { ...COMMENT, likes: 1 }, path: COMMENT_PATH }],
      priorVote: { uid: "uid-1", value: 1 },
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetId: "root1", vote: 1, mangaId: "m1" }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { likes: number; action: string; myVote: number | null };
    expect(body).toMatchObject({ likes: 0, action: "removed", myVote: null });
    expect(txOps.find((op) => op.kind === "delete")).toBeDefined();
  });

  it("switches a dislike into a like", async () => {
    const { db } = fakeDb({
      commentDocs: [{ data: { ...COMMENT, likes: 0, dislikes: 1 }, path: COMMENT_PATH }],
      priorVote: { uid: "uid-1", value: -1 },
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetId: "root1", vote: 1, mangaId: "m1" }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { likes: number; dislikes: number; action: string; myVote: number };
    expect(body).toMatchObject({ likes: 1, dislikes: 0, action: "switched", myVote: 1 });
  });

  it("casts a review vote via the direct review path", async () => {
    const { db, txOps } = fakeDb({ reviewData: REVIEW });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetType: "review", targetId: "rev1", mangaId: "m1", vote: -1 }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { likes: number; dislikes: number; myVote: number };
    expect(body).toMatchObject({ likes: 2, dislikes: 1, myVote: -1 });
    expect(txOps.find((op) => op.kind === "set")).toBeDefined();
  });

  it("still reports success when the like fan-out throws", async () => {
    const { db, notificationWrites } = fakeDb({
      reviewData: REVIEW,
      deviceTokens: ["device-token-1"],
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    vi.mocked(getAdminMessaging).mockReturnValue({
      sendEachForMulticast: vi.fn().mockRejectedValue(new Error("fcm down")),
    } as never);

    // The fan-out catches internally (sendEachForMulticast rejection is
    // swallowed per-chunk); the vote itself must stay 200 either way.
    const res = await POST(req({ targetType: "review", targetId: "rev1", mangaId: "m1", vote: 1 }));
    expect(res.status).toBe(200);
    // The author notification doc is still written (independent of FCM).
    expect(notificationWrites).toHaveLength(1);
    expect(notificationWrites[0].data).toMatchObject({
      type: "REVIEW_REACTION",
      mangaId: "m1",
      commentId: null,
    });
  });

  it("refuses self-votes with 403", async () => {
    const { db } = fakeDb({
      commentDocs: [{ data: { ...COMMENT, authorUid: "uid-1" }, path: COMMENT_PATH }],
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetId: "root1", vote: 1, mangaId: "m1" }));
    expect(res.status).toBe(403);
  });

  it("returns 404 when the comment does not exist", async () => {
    const { db } = fakeDb({ commentDocs: [] });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetId: "missing", vote: 1 }));
    expect(res.status).toBe(404);
  });

  it("returns 409 for ambiguous legacy ids without a manga hint", async () => {
    const { db } = fakeDb({
      commentDocs: [
        { data: { ...COMMENT, mangaId: "m1" }, path: "community_manga/m1/comments/root1" },
        { data: { ...COMMENT, mangaId: "m2" }, path: "community_manga/m2/comments/root1" },
      ],
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetId: "root1", vote: 1 }));
    expect(res.status).toBe(409);
  });

  it("resolves ambiguous ids with a mangaId hint", async () => {
    const { db } = fakeDb({
      commentDocs: [
        { data: { ...COMMENT, mangaId: "m1" }, path: "community_manga/m1/comments/root1" },
        { data: { ...COMMENT, mangaId: "m2" }, path: "community_manga/m2/comments/root1" },
      ],
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ targetId: "root1", vote: 1, mangaId: "m2" }));
    expect(res.status).toBe(200);
  });

  it("rejects malformed payloads with 400", async () => {
    const { db } = fakeDb({ commentDocs: [{ data: COMMENT, path: COMMENT_PATH }] });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    expect((await POST(req({ targetId: "root1", vote: 2 }))).status).toBe(400);
    expect((await POST(req({ targetId: "root1", vote: "1" }))).status).toBe(400);
    expect((await POST(req({ vote: 1 }))).status).toBe(400);
    expect((await POST(req({ targetId: "a/b", vote: 1 }))).status).toBe(400);
    expect((await POST(req({ targetType: "review", targetId: "rev1", vote: 1 }))).status).toBe(400);
    expect((await POST(req({ targetId: "root1", vote: 1, mangaId: 42 }))).status).toBe(400);
  });

  it("supports the legacy commentId field", async () => {
    const { db } = fakeDb({ commentDocs: [{ data: COMMENT, path: COMMENT_PATH }] });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ commentId: "root1", vote: 1, mangaId: "m1" }));
    expect(res.status).toBe(200);
  });

  it("returns 401 without a token", async () => {
    mockAppUnauthorized();
    expect((await POST(req({ targetId: "root1", vote: 1 }))).status).toBe(401);
  });

  it("returns 403 for anonymous users", async () => {
    mockAppAnon();
    expect((await POST(req({ targetId: "root1", vote: 1 }))).status).toBe(403);
  });

  it("rate-limits with 429", async () => {
    vi.mocked(allowAppMutation).mockResolvedValue(false);
    expect((await POST(req({ targetId: "root1", vote: 1 }))).status).toBe(429);
  });
});
