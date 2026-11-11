import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "fs";
import { fileURLToPath } from "url";
import path from "path";
import {
  validateSourceApiHostParams,
  validateSourceBaseUrlParams,
} from "./validate";

// U-30 residual — the shipped Remote Config template must stay inside the
// server-side allow-lists.
//
// `remoteconfig.template.json` (repo root) is the template a fresh
// `firebase deploy --only remoteconfig` publishes. Every `source_*_base_url`
// / `source_*_api_host` default in it therefore has to be a value the
// dashboard would ACCEPT (the PUT gate refuses anything else), and it must
// cover the ids the app actually reads — otherwise a domain move is still
// an app release, and a stale id (rockmanga) sits in the template forever.

const here = path.dirname(fileURLToPath(import.meta.url));
const templatePath = path.resolve(here, "../../../remoteconfig.template.json");

interface RcTemplate {
  parameters?: Record<string, { defaultValue?: { value?: string } }>;
}

function loadTemplate(): RcTemplate | null {
  if (!existsSync(templatePath)) return null;
  return JSON.parse(readFileSync(templatePath, "utf8")) as RcTemplate;
}

describe("remoteconfig.template.json vs the U-30 allow-lists", () => {
  it("publishes only allow-listed source base URLs", () => {
    const template = loadTemplate();
    if (!template) return; // standalone dashboard checkout — no template
    const baseUrlParams: Record<string, string> = {};
    for (const [key, param] of Object.entries(template.parameters ?? {})) {
      if (/^source_.*_base_url$/.test(key)) baseUrlParams[key] = String(param.defaultValue?.value ?? "");
    }
    expect(Object.keys(baseUrlParams).length).toBeGreaterThan(0);
    const result = validateSourceBaseUrlParams(baseUrlParams);
    expect(result.ok, result.error).toBe(true);
  });

  it("publishes only allow-listed source API hosts", () => {
    const template = loadTemplate();
    if (!template) return;
    const apiHostParams: Record<string, string> = {};
    for (const [key, param] of Object.entries(template.parameters ?? {})) {
      if (/^source_.*_api_host$/.test(key)) apiHostParams[key] = String(param.defaultValue?.value ?? "");
    }
    const result = validateSourceApiHostParams(apiHostParams);
    expect(result.ok, result.error).toBe(true);
  });

  it("ships a template entry for every RC-overridable API host", () => {
    const template = loadTemplate();
    if (!template) return;
    // U-25: the app reads these three keys (SourceDomainOverrides). Without a
    // template entry an API-host domain move needs an app release.
    expect(template.parameters?.source_azora_api_host?.defaultValue?.value).toBe("api.azorafly.com");
    expect(template.parameters?.source_mangasid_api_host?.defaultValue?.value).toBe("api.mangasid.com");
    expect(template.parameters?.source_meshmanga_api_host?.defaultValue?.value).toBe("appswat.com");
  });

  it("carries no key for a removed source", () => {
    const template = loadTemplate();
    if (!template) return;
    const keys = Object.keys(template.parameters ?? {});
    // rockmanga died upstream and was deleted from the app; its template
    // keys would be refused by the allow-list and are dead weight.
    expect(keys.filter((k) => k.includes("rockmanga"))).toEqual([]);
  });
});
