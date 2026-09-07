/**
 * Shared typed API client for dashboard pages.
 *
 * Every page used to hand-roll `fetch → .json() → setState`, most without
 * `res.ok` checks — a 500 HTML page or expired-session redirect threw
 * `SyntaxError` in `.json()` and left a misleading "empty" UI. All requests
 * go through here: non-2xx becomes a typed `ApiError` (with `status` for
 * 403 → permission empty-states), aborts propagate as `AbortError`.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body: string;

  constructor(status: number, body: string) {
    super(body || `Request failed with status ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }

  get forbidden(): boolean {
    return this.status === 403;
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, init);
  } catch (e) {
    // Network failure / abort: rethrow untouched (callers check AbortError).
    throw e;
  }
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new ApiError(res.status, body.slice(0, 500));
  }
  return (await res.json()) as T;
}

/** `true` when the fetch failure is an intentional abort (not an error). */
export function isAbortError(e: unknown): boolean {
  return e instanceof Error && e.name === "AbortError";
}
