import { describe, expect, it } from "vitest";
import { pickLatestDatedTable } from "./bigquery";

// Table discovery for Firebase export datasets (#34): dated tables pile up,
// so picking must land on the latest — an old table makes the 7-day query
// window come back empty while data exists.

describe("pickLatestDatedTable", () => {
  const tables = [
    "com_exapps_mangaworld_ANDROID_20250801",
    "com_exapps_mangaworld_ANDROID_20250909",
    "com_exapps_mangaworld_ANDROID_20250901",
    "INFORMATION_SCHEMA",
  ];

  it("picks the latest matching table, not the oldest", () => {
    expect(
      pickLatestDatedTable(tables, (t) => t.toLowerCase().includes("android"))
    ).toBe("com_exapps_mangaworld_ANDROID_20250909");
  });

  it("ignores INFORMATION_SCHEMA even when it sorts last", () => {
    expect(pickLatestDatedTable(["a_20240101", "INFORMATION_SCHEMA"], () => true)).toBe(
      "a_20240101"
    );
  });

  it("returns null when nothing usable exists", () => {
    expect(pickLatestDatedTable([], () => true)).toBeNull();
    expect(pickLatestDatedTable(["INFORMATION_SCHEMA"], () => true)).toBeNull();
    expect(pickLatestDatedTable(tables, () => false)).toBeNull();
  });

  it("falls back across predicates like the performance route does", () => {
    const android = pickLatestDatedTable(tables, (t) => t.includes("ios"));
    const picked = android ?? pickLatestDatedTable(tables, () => true);
    expect(picked).toBe("com_exapps_mangaworld_ANDROID_20250909");
  });
});
