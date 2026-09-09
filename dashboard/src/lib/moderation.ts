import { getAdminRemoteConfig } from "./firebase-admin";

const MAX_TEXT_LENGTH = 2_000;
const RC_TEMPLATE_TTL_MS = 60_000;

let cachedTemplate: { template: unknown; at: number } | null = null;

async function getCachedRcTemplate() {
  if (cachedTemplate && Date.now() - cachedTemplate.at < RC_TEMPLATE_TTL_MS) {
    return cachedTemplate.template as Awaited<ReturnType<ReturnType<typeof getAdminRemoteConfig>["getTemplate"]>>;
  }
  const template = await getAdminRemoteConfig().getTemplate();
  cachedTemplate = { template, at: Date.now() };
  return template;
}

/**
 * Shared server-side keyword gate (#20). Same source of truth as
 * POST /api/community/moderate (published Remote Config template, read with
 * the Admin SDK so client tampering is irrelevant).
 *
 * Fail-open on transient errors: returns true when the scan itself hiccups —
 * post-hoc moderationReports remain the backstop. Callers must still
 * fail-CLOSED on auth/validation before reaching here.
 */
export async function isTextAllowed(text: string): Promise<boolean> {
  const trimmed = text.trim();
  if (!trimmed) return true;
  if (trimmed.length > MAX_TEXT_LENGTH) return false;
  try {
    // Module-level 60s cache: uncached getTemplate() per request turns Sybil
    // volume into Remote Config quota pressure (fail-open then degrades).
    const template = await getCachedRcTemplate();
    const paramValue = template.parameters?.["community_banned_keywords"]?.defaultValue;
    // RemoteConfigParameterValue is a union — only conditional/default values carry `.value`.
    const rawKeywords = paramValue && "value" in paramValue ? paramValue.value : "";
    const keywords = String(rawKeywords)
      .split(",")
      .map((k) => k.trim().toLowerCase())
      .filter(Boolean);
    if (keywords.length === 0) return true;
    return !keywords.some((keyword) => trimmed.toLowerCase().includes(keyword));
  } catch (scanError) {
    console.error("[moderation] scan failure (fail-open):", scanError instanceof Error ? scanError.message : scanError);
    return true;
  }
}
