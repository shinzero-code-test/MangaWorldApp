import { vi } from "vitest";

/**
 * Chainable Firestore double shared by the U-32 route suites.
 *
 * The Admin SDK is mocked away in every route suite (`vi.mock("@/lib/firebase-admin")`),
 * so each suite needs a stand-in for the reads/writes its route performs. This
 * fake models the subset the dashboard routes actually use:
 *
 *   collection(path) → .doc(id) → .collection(sub)      (nested reads/writes)
 *   collectionGroup(name) → .where(f, op, v) → .get()   (moderation lists)
 *   .orderBy().limit().get()      → query snapshot { docs, empty }
 *   .doc(id).get()                → document snapshot { exists, data() }
 *   .count().get()                → { data: () => ({ count }) }
 *   .set(data, { merge }) / .update(data) / .delete() / .add(data)
 *   batch() → { delete, update, set, commit }
 *   runTransaction(fn) → fn(tx) with get/set/update/create/delete
 *
 * Every mutation is recorded in `writes` so a suite can assert that a route
 * touched (or did NOT touch) a document — the assertion that actually pins
 * "soft delete, not hard delete" and "no write on the refusal path".
 */

export type FakeData = Record<string, unknown>;

export interface FakeWrite {
  op: "set" | "update" | "delete" | "add" | "create";
  path: string;
  docId: string;
  data?: FakeData;
  merge?: boolean;
}

export interface FakeDb {
  /** Firestore-like root: collection(), collectionGroup(), batch(), runTransaction(). */
  db: unknown;
  /** Seed a document at `collection/docId` (path may be nested: "a/b/c"). */
  put: (path: string, docId: string, data: FakeData) => void;
  /** Seed a collectionGroup bucket ("comments" → docId → data). */
  putGroup: (group: string, docId: string, data: FakeData) => void;
  /** Seed a collectionGroup doc that mirrors a real nested document path. */
  putGroupAt: (group: string, docId: string, data: FakeData, refPath: string) => void;
  /** Seed the count() result for a collection path. */
  setCount: (path: string, count: number) => void;
  /** All recorded writes, in order. */
  writes: FakeWrite[];
  /** Read a stored document back (path → docId → data). */
  read: (path: string, docId: string) => FakeData | undefined;
  /** Writes recorded for a given path. */
  writesFor: (path: string) => FakeWrite[];
}

function lastSegment(path: string): string {
  const parts = path.split("/").filter(Boolean);
  return parts[parts.length - 1] ?? "";
}

export function createFakeDb(): FakeDb {
  const paths = new Map<string, Map<string, FakeData>>();
  const groups = new Map<string, Map<string, FakeData>>();
  const counts = new Map<string, number>();
  const writes: FakeWrite[] = [];

  const storeFor = (path: string): Map<string, FakeData> => {
    let store = paths.get(path);
    if (!store) {
      store = new Map<string, FakeData>();
      paths.set(path, store);
    }
    return store;
  };

  const groupFor = (name: string): Map<string, FakeData> => {
    let store = groups.get(name);
    if (!store) {
      store = new Map<string, FakeData>();
      groups.set(name, store);
    }
    return store;
  };

  const snapshotOf = (id: string, data: FakeData | undefined, ref?: Node) => ({
    id,
    exists: data !== undefined,
    data: () => data,
    // The real DocumentSnapshot.ref is a DocumentReference — batch/transaction
    // calls resolve the target path through it, so it must be the node.
    ref: ref ?? { id, path: id, __path: id },
  });

  type Node = Record<string, unknown> & { __path: string; id?: string };

  /** Build a full parent chain for a path (users/uid/preferences/achievements). */
  const chainFor = (p: string): Node => {
    const parts = p.split("/").filter(Boolean);
    let current: Node | undefined;
    let acc = "";
    let index = 0;
    for (const part of parts) {
      acc = acc ? `${acc}/${part}` : part;
      current = createNode(acc, index % 2 === 0 ? "collection" : "doc", undefined, current);
      index += 1;
    }
    return current as Node;
  };

  const createNode = (
    path: string,
    kind: "doc" | "collection" | "group",
    groupName?: string,
    parentNode?: Node,
  ): Node => {
    const filters: Array<[string, unknown]> = [];
    const node: Node = {
      __path: path,
      ...(kind === "doc" ? { id: lastSegment(path) } : {}),
      // The real DocumentReference exposes `.parent`; routes walk it
      // (users/{uid}/preferences/achievements → uid).
      ...(parentNode ? { parent: parentNode } : {}),
    };

    // A document lives in its PARENT collection store under its own id:
    // collection("publicProfiles").doc("u1") reads
    // store["publicProfiles"]["u1"] — the same slot `put("publicProfiles",
    // "u1", …)` seeds.
    const docId = lastSegment(path);
    const parentPath = kind === "doc" ? path.slice(0, path.length - docId.length - 1) : path;

    const source = (): Map<string, FakeData> =>
      kind === "group" ? groupFor(groupName ?? "") : storeFor(parentPath);

    const matching = (): Array<{ id: string; data: FakeData }> => {
      let entries = [...source().entries()];
      if (filters.length > 0) {
        entries = entries.filter(([, data]) =>
          filters.every(([field, value]) => data[field] === value),
        );
      }
      return entries.map(([id, data]) => ({ id, data }));
    };

    node.doc = (id: string) => createNode(`${path}/${id}`, "doc", undefined, node);
    node.collection = (name: string) => createNode(`${path}/${name}`, "collection", undefined, node);
    node.where = (field: string, _op: string, value: unknown) => {
      filters.push([field, value]);
      return node;
    };
    node.orderBy = () => node;
    node.limit = () => node;
    node.startAfter = () => node;
    node.offset = () => node;
    node.count = () => ({
      get: async () => ({
        data: () => ({
          count:
            kind === "group"
              ? matching().length
              : (counts.get(parentPath) ?? (kind === "collection" ? matching().length : 0)),
        }),
      }),
    });
    node.get = async () => {
      if (kind === "doc") {
        const data = source().get(docId);
        return snapshotOf(docId, data, node);
      }
      const docs = matching();
      return {
        docs: docs.map((d) => {
          // A collectionGroup doc may carry the real document path so
          // `ref.parent.parent.id` resolves (users/{uid}/preferences/…).
          const refPath =
            kind === "group" && typeof d.data.__refPath === "string"
              ? d.data.__refPath
              : `${parentPath}/${d.id}`;
          return snapshotOf(d.id, d.data, chainFor(refPath));
        }),
        empty: docs.length === 0,
        size: docs.length,
      };
    };
    node.set = async (data: FakeData, options?: { merge?: boolean }) => {
      writes.push({ op: "set", path: parentPath, docId, data, merge: options?.merge === true });
      const store = storeFor(parentPath);
      const existing = store.get(docId) ?? {};
      store.set(docId, options?.merge ? { ...existing, ...data } : { ...data });
      return snapshotOf(docId, store.get(docId));
    };
    node.update = async (data: FakeData) => {
      writes.push({ op: "update", path: parentPath, docId, data });
      const store = storeFor(parentPath);
      store.set(docId, { ...(store.get(docId) ?? {}), ...data });
      return snapshotOf(docId, store.get(docId));
    };
    node.delete = async () => {
      writes.push({ op: "delete", path: parentPath, docId });
      storeFor(parentPath).delete(docId);
    };
    node.add = async (data: FakeData) => {
      const id = `gen-${writes.length + 1}`;
      writes.push({ op: "add", path: parentPath, docId: id, data });
      storeFor(parentPath).set(id, { ...data });
      return snapshotOf(id, storeFor(parentPath).get(id));
    };

    return node;
  };

  /** Document refs address their PARENT collection store (see createNode). */
  const resolveDoc = (ref: Node) => {
    const docId = lastSegment(ref.__path);
    const path = ref.__path.slice(0, ref.__path.length - docId.length - 1);
    return { path, docId };
  };

  const batch = () => {
    const ops: Array<{ op: FakeWrite["op"]; ref: Node; data?: FakeData; merge?: boolean }> = [];
    const apply = (op: (typeof ops)[number]) => {
      const { path, docId } = resolveDoc(op.ref);
      if (op.op === "delete") {
        writes.push({ op: "delete", path, docId });
        storeFor(path).delete(docId);
        return;
      }
      const data = op.data ?? {};
      const store = storeFor(path);
      const existing = store.get(docId) ?? {};
      store.set(docId, op.merge ? { ...existing, ...data } : { ...data });
      writes.push({ op: op.op, path, docId, data, merge: op.merge });
    };
    return {
      delete: (ref: Node) => ops.push({ op: "delete", ref }),
      set: (ref: Node, data: FakeData, options?: { merge?: boolean }) =>
        ops.push({ op: "set", ref, data, merge: options?.merge === true }),
      update: (ref: Node, data: FakeData) => ops.push({ op: "update", ref, data }),
      commit: async () => {
        ops.forEach(apply);
        return [];
      },
    };
  };

  const transaction = () => {
    const tx = {
      get: async (ref: Node) => {
        const { path, docId } = resolveDoc(ref);
        return snapshotOf(docId, storeFor(path).get(docId));
      },
      set: (ref: Node, data: FakeData) => {
        const { path, docId } = resolveDoc(ref);
        writes.push({ op: "set", path, docId, data });
        storeFor(path).set(docId, { ...data });
      },
      update: (ref: Node, data: FakeData) => {
        const { path, docId } = resolveDoc(ref);
        writes.push({ op: "update", path, docId, data });
        const store = storeFor(path);
        store.set(docId, { ...(store.get(docId) ?? {}), ...data });
      },
      create: (ref: Node, data: FakeData) => {
        const { path, docId } = resolveDoc(ref);
        writes.push({ op: "create", path, docId, data });
        storeFor(path).set(docId, { ...data });
      },
      delete: (ref: Node) => {
        const { path, docId } = resolveDoc(ref);
        writes.push({ op: "delete", path, docId });
        storeFor(path).delete(docId);
      },
    };
    return tx;
  };

  const db = {
    collection: (name: string) => createNode(name, "collection"),
    collectionGroup: (name: string) => createNode(`__group__/${name}`, "group", name),
    batch,
    runTransaction: async (fn: (tx: ReturnType<typeof transaction>) => Promise<unknown>) =>
      fn(transaction()),
  };

  return {
    db,
    writes,
    put: (path: string, docId: string, data: FakeData) => storeFor(path).set(docId, data),
    putGroup: (group: string, docId: string, data: FakeData) => groupFor(group).set(docId, data),
    /**
     * Seed a collectionGroup doc that mirrors a real nested document
     * (e.g. users/{uid}/preferences/achievements) so `ref.parent.parent.id`
     * resolves to the uid.
     */
    putGroupAt: (group: string, docId: string, data: FakeData, refPath: string) =>
      groupFor(group).set(docId, { ...data, __refPath: refPath }),
    setCount: (path: string, count: number) => counts.set(path, count),
    read: (path: string, docId: string) => storeFor(path).get(docId),
    writesFor: (path: string) => writes.filter((w) => w.path === path),
  };
}

/** Messaging double: records the multicast payloads a route pushed. */
export function fakeMessaging() {
  const sends: Array<{ tokens: string[]; data: Record<string, string> }> = [];
  const sendEachForMulticast = vi.fn(async (message: { tokens: string[]; data: Record<string, string> }) => {
    sends.push({ tokens: message.tokens, data: message.data });
    return { successCount: message.tokens.length, failureCount: 0, responses: [] };
  });
  return { messaging: { sendEachForMulticast }, sends };
}
