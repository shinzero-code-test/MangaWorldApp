import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "fs";
import { fileURLToPath } from "url";
import path from "path";
import {
  boundedString,
  isPlainObject,
  isValidEmail,
  isValidFolder,
  validateFirestoreDoc,
  validateRemoteConfigParams,
  validateSourceApiHostParams,
  validateSourceBaseUrlParams,
  validateSourceDomainParams,
  SOURCE_API_HOST_ALLOWLIST,
  SOURCE_BASE_URL_ALLOWLIST,
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
    expect(isValidEmail(`${"a".repeat(320)}@b.co`)).toBe(false);
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

describe("validateSourceBaseUrlParams (U-30)", () => {
  it("accepts allow-listed https origins (incl. www. variant)", () => {
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "https://olympustaff.com" }).ok).toBe(true);
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "https://www.olympustaff.com" }).ok).toBe(true);
    expect(validateSourceBaseUrlParams({ source_hijala_base_url: "https://hijala.com" }).ok).toBe(true);
  });

  it("ignores non-source keys (additive gate)", () => {
    expect(validateSourceBaseUrlParams({ app_theme: "dark", limit: 10 }).ok).toBe(true);
    expect(validateSourceBaseUrlParams({}).ok).toBe(true);
  });

  it("rejects non-allow-listed hosts with 400-equivalent verdict", () => {
    const result = validateSourceBaseUrlParams({ source_olympus_base_url: "https://evil.example" });
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/allow-listed/);
  });

  it("rejects http, paths, credentials, ports, and unknown ids", () => {
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "http://olympustaff.com" }).ok).toBe(false);
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "https://olympustaff.com/manga" }).ok).toBe(false);
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "https://user:pass@olympustaff.com" }).ok).toBe(false);
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "https://olympustaff.com:8443" }).ok).toBe(false);
    expect(validateSourceBaseUrlParams({ source_evil_base_url: "https://evil.example" }).ok).toBe(false);
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "not a url" }).ok).toBe(false);
    expect(validateSourceBaseUrlParams({ source_olympus_base_url: "" }).ok).toBe(false);
  });
});

// The 17 shipped source ids. Mirrors the app's BuiltinSourceIds.ALL
// (app/src/main/kotlin/.../core/source/plugins/BuiltinSourceIds.kt) — the
// dashboard allow-list and the app's source set must never drift apart, or
// a legitimate source becomes un-overridable (or an unknown id slips into
// the allow-list).
const BUILTIN_SOURCE_IDS = [
  "olympus",
  "azora",
  "starz",
  "mangasid",
  "meshmanga",
  "asq3",
  "lekmanga",
  "lekmangaonline",
  "likemanga",
  "linkmanga",
  "mangaleko",
  "mangalionz",
  "areascans",
  "hijala",
  "lavascans",
  "stellarsaber",
  "procomic",
];

describe("source-domain allow-lists (U-30)", () => {
  it("covers exactly the 17 shipped source ids", () => {
    expect(Object.keys(SOURCE_BASE_URL_ALLOWLIST).sort()).toEqual([...BUILTIN_SOURCE_IDS].sort());
    expect(Object.keys(SOURCE_BASE_URL_ALLOWLIST)).toHaveLength(17);
  });

  it("matches the app's BuiltinSourceIds.ALL when the app tree is present", () => {
    // The dashboard is also checked out standalone in some pipelines; the
    // cross-check is skipped there, but never silently: the pinned list
    // above is the contract.
    const here = path.dirname(fileURLToPath(import.meta.url));
    const kotlin = path.resolve(
      here,
      "../../../app/src/main/kotlin/com/exapps/mangaworld/core/source/plugins/BuiltinSourceIds.kt",
    );
    if (!existsSync(kotlin)) return;
    const source = readFileSync(kotlin, "utf8");
    const block = /val ALL: Set<String> = setOf\(([\s\S]*?)\)/.exec(source);
    expect(block).not.toBeNull();
    const ids = [...(block?.[1] ?? "").matchAll(/"([a-z0-9]+)"/g)].map((m) => m[1]).sort();
    expect(ids).toEqual([...BUILTIN_SOURCE_IDS].sort());
  });

  it("has no removed source id in either allow-list", () => {
    // rockmanga died upstream and was removed from the app; a leftover
    // template default for it would be refused by the allow-list.
    expect(Object.keys(SOURCE_BASE_URL_ALLOWLIST)).not.toContain("rockmanga");
    expect(Object.keys(SOURCE_API_HOST_ALLOWLIST)).not.toContain("rockmanga");
  });
});

describe("validateSourceApiHostParams (U-30 residual)", () => {
  it("accepts the three app API hosts (host-only or https://host)", () => {
    expect(validateSourceApiHostParams({ source_azora_api_host: "api.azorafly.com" }).ok).toBe(true);
    expect(validateSourceApiHostParams({ source_mangasid_api_host: "api.mangasid.com" }).ok).toBe(true);
    expect(validateSourceApiHostParams({ source_meshmanga_api_host: "appswat.com" }).ok).toBe(true);
    expect(validateSourceApiHostParams({ source_azora_api_host: "https://api.azorafly.com" }).ok).toBe(true);
  });

  it("ignores non-api-host keys (additive gate)", () => {
    expect(validateSourceApiHostParams({ app_theme: "dark", limit: 10 }).ok).toBe(true);
    expect(validateSourceApiHostParams({}).ok).toBe(true);
  });

  it("rejects a host that is not allow-listed for that id", () => {
    const result = validateSourceApiHostParams({ source_azora_api_host: "evil.example" });
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/allow-listed/);
    // Cross-source hosts are refused too.
    expect(validateSourceApiHostParams({ source_azora_api_host: "api.mangasid.com" }).ok).toBe(false);
  });

  it("rejects unknown ids, paths, ports, empty values and non-strings", () => {
    expect(validateSourceApiHostParams({ source_evil_api_host: "evil.example" }).ok).toBe(false);
    expect(validateSourceApiHostParams({ source_azora_api_host: "api.azorafly.com/v2" }).ok).toBe(false);
    expect(validateSourceApiHostParams({ source_azora_api_host: "api.azorafly.com:8443" }).ok).toBe(false);
    expect(validateSourceApiHostParams({ source_azora_api_host: "" }).ok).toBe(false);
    expect(validateSourceApiHostParams({ source_azora_api_host: 42 }).ok).toBe(false);
  });
});

describe("validateSourceDomainParams (the single PUT gate)", () => {
  it("runs BOTH the base-URL and the API-host gates", () => {
    expect(validateSourceDomainParams({ source_olympus_base_url: "https://olympustaff.com" }).ok).toBe(true);
    expect(validateSourceDomainParams({ source_azora_api_host: "api.azorafly.com" }).ok).toBe(true);
    // A bad base URL is caught …
    expect(validateSourceDomainParams({ source_olympus_base_url: "https://evil.example" }).ok).toBe(false);
    // … and so is a bad API host (the half that used to be missing).
    expect(validateSourceDomainParams({ source_azora_api_host: "evil.example" }).ok).toBe(false);
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
