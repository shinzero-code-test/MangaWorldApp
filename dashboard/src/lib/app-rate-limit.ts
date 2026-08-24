import { consumeRateLimit } from "./security";

export { consumeRateLimit };

/**
 * App-facing mutation limiter. Backed by Firestore fixed-window counters so the
 * limit holds across serverless instances (M-3); falls back to per-instance
 * memory only if Firestore is unavailable.
 */
export async function allowAppMutation(
  key: string,
  limit: number,
  windowMs: number
): Promise<boolean> {
  const result = await consumeRateLimit("app-mutation", key, limit, windowMs);
  return result.allowed;
}
