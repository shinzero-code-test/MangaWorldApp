import { describe, expect, it } from "vitest";
import {
  boundedString,
  isPlainObject,
  isValidEmail,
  isValidFolder,
  validateFirestoreDoc,
  validateRemoteConfigParams,
} from "./validate";

// Pure request-body validators for admin mutation endpoints (M-6).
// No Firestore/network/env access — safe to run in plain vitest.

describe("isPlainObject", () => {
  it("accepts plain objects", () => {
    expect(isPlainObject({})).toBe(true);
    expect(isPlainObject({ a: 1 })).toBe(true);
  });

  it("rejects null, arrays, and primitives", () => {
    expect(isPlainObject(null)).toBe(false);
    expect(isPlainObject(undefined)).toBe(false);
    expect(isPlainObject([])).toBe(false);
    expect(isPlainObject([1, 2])).toBe(false);
    expect(isPlainObject("obj")).toBe(false);
    expect(isPlainObject(42)).toBe(false);
    expect(isPlainObject(true)).toBe(false);
  });
});

describe("boundedString", () => {
  it("trims and returns strings within range", () => {
    expect(boundedString("  hello  ", 10)).toBe("hello");
    expect(boundedString("a", 1)).toBe("a");
    expect(boundedString("abc", 3)).toBe("abc");
  });

  it("preserves unicode content", () => {
    expect(boundedString("  مانجا  ", 10)).toBe("مانجا");
    expect(boundedString("🎌test", 10)).toBe("🎌test");
  });

  it("rejects empty, blank, and oversized input", () => {
    expect(boundedString("", 10)).toBeNull();
    expect(boundedString("   ", 10)).toBeNull();
    expect(boundedString("\n\t ", 10)).toBeNull();
    expect(boundedString("toolong", 3)).toBeNull();
    expect(boundedString("x".repeat(11), 10)).toBeNull();
  });

  it("rejects non-strings", () => {
    expect(boundedString(null, 10)).toBeNull();
    expect(boundedString(undefined, 10)).toBeNull();
    expect(boundedString(123, 10)).toBeNull();
    expect(boundedString({}, 10)).toBeNull();
    expect(boundedString(["a"], 10)).toBeNull();
  });
});

describe("isValidEmail", () => {
  it("accepts well-formed addresses", () => {
    expect(isValidEmail("user@example.com")).toBe(true);
    expect(isValidEmail("a.b+tag@sub.domain.co")).toBe(true);
  });

  it("rejects malformed addresses", () => {
    expect(isValidEmail("")).toBe(false);
    expect(isValidEmail("plainaddress")).toBe(false);
    expect(isValidEmail("missing@domain")).toBe(false);
    expect(isValidEmail("missing-at.com")).toBe(false);
    expect(isValidEmail("a@b.c")).toBe(false); // TLD needs 2+ chars
    expect(isValidEmail("a b@c.com")).toBe(false);
    expect(isValidEmail(" a@b.co ")).toBe(false); // untrimmed padding
  });

  it("rejects overlong and non-string input", () => {
    expect(isValidEmail(`${"a".repeat(310)}@b.co`)).toBe(false);
    expect(isValidEmail(null)).toBe(false);
    expect(isValidEmail(undefined)).toBe(false);
    expect(isValidEmail(123)).toBe(false);
  });
});

describe("validateRemoteConfigParams", () => {
  it("accepts primitive-only maps", () => {
    expect(validateRemoteConfigParams({ flag: true, limit: 10, name: "x" }).ok).toBe(true);
  });

  it("rejects non-object input", () => {
    expect(validateRemoteConfigParams(null).ok).toBe(false);
    expect(validateRemoteConfigParams([]).ok).toBe(false);
    expect(validateRemoteConfigParams("params").ok).toBe(false);
    expect(validateRemoteConfigParams(undefined).ok).toBe(false);
  });

  it("rejects empty and oversized maps", () => {
    expect(validateRemoteConfigParams({}).ok).toBe(false);
    const big: Record<string, boolean> = {};
    for (let i = 0; i < 201; i++) big[`k${i}`] = true;
    const result = validateRemoteConfigParams(big);
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/count/);
  });

  it("rejects keys outside the identifier charset", () => {
    for (const key of ["1abc", "has-dash", "has space", "مفتاح", ""]) {
      const result = validateRemoteConfigParams({ [key]: "v" });
      expect(result.ok).toBe(false);
      expect(result.error).toMatch(/key/);
    }
  });

  it("rejects non-primitive values", () => {
    for (const value of [{}, [], null, undefined]) {
      const result = validateRemoteConfigParams({ good_key: value });
      expect(result.ok).toBe(false);
      expect(result.error).toMatch(/string\/number\/boolean/);
    }
  });

  it("enforces the 2000-char string boundary", () => {
    expect(validateRemoteConfigParams({ k: "x".repeat(2000) }).ok).toBe(true);
    const over = validateRemoteConfigParams({ k: "x".repeat(2001) });
    expect(over.ok).toBe(false);
    expect(over.error).toMatch(/2000/);
  });
});

describe("validateFirestoreDoc", () => {
  it("accepts plain payloads", () => {
    expect(validateFirestoreDoc({ title: "مانجا", count: 3 }).ok).toBe(true);
    expect(validateFirestoreDoc({}).ok).toBe(true);
  });

  it("rejects non-objects", () => {
    expect(validateFirestoreDoc(null).ok).toBe(false);
    expect(validateFirestoreDoc([1]).ok).toBe(false);
    expect(validateFirestoreDoc("doc").ok).toBe(false);
  });

  it("rejects documents over 100KB", () => {
    const result = validateFirestoreDoc({ blob: "x".repeat(100_001) });
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/100KB/);
  });

  it("rejects reserved __-prefixed keys at any depth", () => {
    expect(validateFirestoreDoc({ __secret: 1 }).ok).toBe(false);
    // NOTE: {"__proto__":{}} as a literal sets the prototype instead of an
    // own key, so the nested case is built via JSON.parse to carry a real
    // own "__proto__" key like attacker JSON would.
    expect(
      validateFirestoreDoc(JSON.parse('{"a":{"__proto__":{}}}')).ok
    ).toBe(false);
    expect(validateFirestoreDoc({ a: { b: { __x: 1 } } }).ok).toBe(false);
  });

  it("rejects dotted keys at any depth", () => {
    expect(validateFirestoreDoc({ "a.b": 1 }).ok).toBe(false);
    expect(validateFirestoreDoc({ a: { "b.c": 1 } }).ok).toBe(false);
  });

  it("allows single-underscore keys", () => {
    expect(validateFirestoreDoc({ _a: 1, a: { _b: 2 } }).ok).toBe(true);
  });
});

describe("isValidFolder", () => {
  it("accepts relative segments", () => {
    expect(isValidFolder("manga")).toBe(true);
    expect(isValidFolder("manga/covers")).toBe(true);
    expect(isValidFolder("a1-_b/c-d")).toBe(true);
  });

  it("rejects traversal, empty segments, and bad starts", () => {
    expect(isValidFolder("")).toBe(false);
    expect(isValidFolder("a//b")).toBe(false);
    expect(isValidFolder("a/../b")).toBe(false);
    expect(isValidFolder("..")).toBe(false);
    expect(isValidFolder("/manga")).toBe(false);
    expect(isValidFolder("_manga")).toBe(false);
    expect(isValidFolder("-manga")).toBe(false);
  });

  it("rejects spaces, dots, unicode, and non-strings", () => {
    expect(isValidFolder("my folder")).toBe(false);
    expect(isValidFolder("foo.bar")).toBe(false);
    expect(isValidFolder("مانجا")).toBe(false);
    expect(isValidFolder(null)).toBe(false);
    expect(isValidFolder(undefined)).toBe(false);
    expect(isValidFolder(123)).toBe(false);
  });

  it("rejects overlong folders", () => {
    expect(isValidFolder("a".repeat(65))).toBe(false);
    expect(isValidFolder("a".repeat(64))).toBe(true);
  });
});
