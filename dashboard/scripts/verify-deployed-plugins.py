#!/usr/bin/env python3
"""Read-only verifier for the DEPLOYED plugin distribution (CI: verify-deployed-plugins).

`sign-plugin.py --check-published` proves the *committed* tree is well-formed and
signed. It cannot prove what is actually SERVED from
`https://mangaworld-admin.vercel.app/plugins/` — and that gap already cost a real
outage: promotion is a manual `vercel --prod` (`vercel.json` sets
`deploymentEnabled:false`), so the distribution "had NEVER been live" while every
repository-side check stayed green.

This script closes the gap. It fetches the live index and every manifest (plus the
sibling `source.js` of script payloads) with curl, verifies each manifest's
Ed25519 signature against the PUBLIC PIN committed in
`dashboard/lib/plugin-keys.ts` — reusing `sign-plugin.py`'s JCS canonicalizer and
verifier, so the bytes are checked with the exact same code path the app and the
local gate use — and prints the trust calendar.

HTTP is done with `curl` (ubiquitous on the runner, no third-party Python deps);
only `cryptography` is needed, exactly as in the existing gate.

Usage:
  python3 verify-deployed-plugins.py \
      --index-url https://mangaworld-admin.vercel.app/plugins/index.json \
      --mirror "$RUNNER_TEMP/deployed-plugins" \
      [--pin-source dashboard/lib/plugin-keys.ts] \
      [--archive-dir dashboard/plugins/_archive] \
      [--trust-window-days 90] [--warn-window-days 30]

Exit codes: 0 = every served byte verified; 1 = at least one violation (all are
printed, not just the first) or an unusable environment; 2 = argparse CLI misuse.

Read-only by construction: it only issues GETs to the public distribution host and
writes a local mirror for forensics. It never deploys, and it needs no secrets —
the deployed distribution is public and the trust pin is a committed public key.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

# ── Constants mirrored from the app so CI fails exactly where the app would ──
# PluginDistribution.DEFAULT_BASE_URL / PluginDistribution.checkManifestUrl
DEFAULT_INDEX_URL = "https://mangaworld-admin.vercel.app/plugins/index.json"
# PluginIndexParser.SCHEMA_VERSION / MAX_INDEX_BYTES / MAX_ENTRIES / ID_REGEX
SCHEMA_VERSION = 1
MAX_INDEX_BYTES = 64 * 1024
MAX_ENTRIES = 1000
ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
APP_VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
# PluginManifestParser.MAX_MANIFEST_BYTES; ScriptContract.SCRIPT_MAX_BYTES
MAX_MANIFEST_BYTES = 64 * 1024
SCRIPT_MAX_BYTES = 512 * 1024
# PluginTrust.MAX_TRUST_AGE_DAYS (90). Re-issue = new issuedAt + version bump +
# re-sign; the same-version bytes are immutable, so an expiring anchor can only be
# fixed by publishing a new version. Warn early enough to actually do that.
TRUST_WINDOW_DAYS = 90
WARN_WINDOW_DAYS = 30
# PluginDistribution.checkManifestUrl rejects userinfo and non-default ports.
ALLOWED_PORTS = (None, "", 443)

CURL_MAX_TIME = 30
SCRIPT_MAX_BYTES_CAP = SCRIPT_MAX_BYTES * 2  # curl --max-filesize headroom

PIN_RE = re.compile(r'PLUGIN_SIGNING_PUBLIC_KEY_B64\s*=\s*"([^"]+)"')
KEY_ID_RE = re.compile(r'PLUGIN_SIGNING_KEY_ID\s*=\s*"([^"]+)"')
HEX64_RE = re.compile(r"^[0-9a-f]{64}$")

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


# ── signer reuse: never re-implement JCS/Ed25519 ──────────────────────────────

def load_signer():
    """Import `sign-plugin.py` (hyphenated filename → importlib)."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sign-plugin.py")
    spec = importlib.util.spec_from_file_location("sign_plugin", path)
    if spec is None or spec.loader is None:
        raise SystemExit("cannot load sign-plugin.py from %s" % path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def read_pin(pin_source):
    """Committed PUBLIC pin + key id from `dashboard/lib/plugin-keys.ts`.

    This file — not the served bytes, not the signer — is the trust root CI must
    verify against, so a swapped pin in either of the other two copies shows up as
    drift here (and `--check-published` asserts all three copies still agree).
    """
    path = os.path.join(REPO, pin_source)
    if not os.path.isfile(path):
        raise SystemExit("pin source not found: %s" % path)
    text = open(path, encoding="utf-8").read()
    pin = PIN_RE.search(text)
    key_id = KEY_ID_RE.search(text)
    if not pin or not key_id:
        raise SystemExit("could not read PLUGIN_SIGNING_PUBLIC_KEY_B64/KEY_ID from %s" % pin_source)
    return pin.group(1), key_id.group(1)


# ── HTTP via curl ─────────────────────────────────────────────────────────────

def fetch(url, cap):
    """GET `url` with curl. Returns (status, body, error, redirects, effective_url).

    Redirects are NOT followed on purpose: a 3xx is drift (the app's fetcher does
    per-hop validation), so it is reported instead of silently resolved.
    """
    cmd = [
        "curl", "--silent", "--show-error",
        "--max-time", str(CURL_MAX_TIME),
        "--max-filesize", str(SCRIPT_MAX_BYTES_CAP),
        "--write-out", "\n%{http_code} %{num_redirects} %{url_effective}",
        url,
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, check=False)
    except FileNotFoundError:
        raise SystemExit("curl is required but not installed")
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace").strip()
        return 0, b"", err or ("curl exit %d" % proc.returncode), "?", "?"
    body, _, tail = proc.stdout.rpartition(b"\n")
    parts = tail.decode("utf-8", "replace").split(" ", 2)
    if len(parts) < 3:
        return 0, b"", "unparseable curl status output", "?", "?"
    try:
        status = int(parts[0])
    except ValueError:
        return 0, b"", "unparseable curl status %r" % parts[0], "?", "?"
    redirects, effective = parts[1], parts[2]
    # `--max-filesize` only fires on a declared Content-Length, so a chunked body
    # is still checked after the fact (same lesson as CappedBytes.read).
    if len(body) > cap:
        return status, body, "body exceeds %d bytes (%d received)" % (cap, len(body)), redirects, effective
    return status, body, "", redirects, effective


def mirror_write(mirror, url, body):
    """Keep the served bytes on disk so a failure has evidence attached."""
    if not mirror or not body:
        return
    rel = urlparse(url).path.lstrip("/") or "index"
    dest = os.path.join(mirror, *rel.split("/"))
    try:
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(body)
    except OSError:
        pass  # the mirror is evidence, never a gate


# ── JSON / time helpers ───────────────────────────────────────────────────────

def strict_json(signer, body, label, problems):
    text = body.decode("utf-8", "replace")
    try:
        node = json.loads(text, object_pairs_hook=signer._no_dupes)
    except ValueError as e:
        problems.append("%s: invalid JSON (%s)" % (label, e))
        return None
    if not isinstance(node, dict):
        problems.append("%s: top level must be an object" % label)
        return None
    return node


def parse_instant(value):
    """ISO-8601 instant → aware datetime, or None (unparseable fails closed)."""
    if not isinstance(value, str) or not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def sibling_url(manifest_url, filename):
    """`…/plugin.json` → `…/source.js` (mirrors ScriptPluginLoader.payloadUrl)."""
    trimmed = manifest_url.strip()
    cut = trimmed.rfind("/")
    return (trimmed[:cut + 1] + filename) if cut >= 0 else trimmed


def host_ok(url, expected_host):
    """https + no userinfo + default port + exact host (PluginDistribution)."""
    u = urlparse(url)
    if u.scheme != "https":
        return "manifestUrl must be https (%s)" % url
    if u.username or u.password:
        return "manifestUrl must not carry userinfo (%s)" % url
    if u.port not in ALLOWED_PORTS:
        return "manifestUrl must not carry a non-default port (%s)" % url
    if (u.hostname or "").lower() != expected_host.lower():
        return "manifestUrl host %r is not the distribution host %r" % (u.hostname, expected_host)
    return None


def engine_skew(kind, engine):
    """Index `kind` vs signed `engine`: logged, never fatal (PluginDistribution)."""
    hinted = {
        "descriptor": {"madara", "mangareader", "astro", "api"},
        "script": {"script"},
        "builtin": {"custom"},
    }.get(str(kind).lower())
    if hinted is None:
        return True
    return str(engine).lower() not in hinted


# ── verification ──────────────────────────────────────────────────────────────

def verify_manifest(signer, pub, key_id, label, text, expect_id, expect_version,
                    trust_window_days, warn_window_days, now, problems, rows,
                    source_js_bytes=None):
    """Signature + schema + trust calendar for one manifest. Returns engine."""
    raw = text.encode("utf-8") if isinstance(text, str) else text
    node = strict_json(signer, raw, label, problems)
    if node is None:
        return None, None
    try:
        sig_key, sig = signer.parse_sig_header(node.get("signature", ""))
    except ValueError as e:
        problems.append("%s: %s" % (label, e))
        return None, None
    if sig_key != key_id:
        problems.append("%s: signed by %r, expected %r" % (label, sig_key, key_id))
    try:
        signer.verify_signature(pub, signer.canonical_manifest_bytes(raw.decode("utf-8")), sig)
    except Exception as ex:
        problems.append("%s: SIGNATURE DOES NOT VERIFY (%s)" % (label, ex))
        return None, None

    pid, ver = node.get("id"), node.get("version")
    if expect_id is not None and pid != expect_id:
        problems.append("%s: manifest id %r != index id %r" % (label, pid, expect_id))
    if expect_version is not None and ver != expect_version:
        problems.append("%s: manifest version %r != index version %r" % (label, ver, expect_version))

    engine = node.get("engine", "")
    sha = node.get("scriptSha256")
    if engine == "script":
        bridge = node.get("bridgeApi")
        if not isinstance(bridge, int) or isinstance(bridge, bool) or bridge < 1:
            problems.append("%s: script requires bridgeApi >= 1" % label)
        if not isinstance(sha, str) or not HEX64_RE.match(sha):
            problems.append("%s: script requires a lowercase-hex scriptSha256" % label)
        elif source_js_bytes is not None:
            actual = hashlib.sha256(source_js_bytes).hexdigest()
            if actual != sha:
                problems.append("%s: source.js sha256 %s != scriptSha256 %s" % (label, actual, sha))
    else:
        # ManifestParser rejects these outright — a payload the app would refuse is
        # distribution drift even when its signature is valid.
        if "bridgeApi" in node:
            problems.append("%s: bridgeApi is script-only" % label)
        if "scriptSha256" in node:
            problems.append("%s: scriptSha256 is script-only" % label)
    if not isinstance(node.get("config", {}), dict):
        problems.append("%s: config must be an object" % label)

    # Trust calendar: PluginTrust.isFresh rejects future anchors and anything older
    # than the 90-day window. A near-expiry anchor is a FAILURE here, not a note —
    # immutability means the fix is a new version + re-sign, so the job must fail
    # while there is still time to publish one.
    issued_at = node.get("issuedAt")
    issued = parse_instant(issued_at)
    expiry = None
    days_left = None
    if issued is None:
        problems.append("%s: issuedAt missing/unparseable (%r)" % (label, issued_at))
    elif issued > now:
        problems.append("%s: issuedAt %s is in the future (untrustworthy anchor)" % (label, issued_at))
    else:
        expiry = issued + timedelta(days=trust_window_days)
        days_left = (expiry - now).days
        if days_left < 0:
            problems.append("%s: trust anchor EXPIRED %d day(s) ago (%s)" % (label, -days_left, issued_at))
        elif days_left < warn_window_days:
            problems.append(
                "%s: trust anchor expires in %d day(s) (%s) — within the %d-day re-issue window; "
                "publish a new version with a fresh issuedAt and re-sign"
                % (label, days_left, expiry.date().isoformat(), warn_window_days))
    rows.append((pid or "?", ver if ver is not None else "?", issued_at or "?",
                 expiry.date().isoformat() if expiry is not None else "?",
                 days_left if days_left is not None else "?", engine or "?"))
    return engine, sha


def print_table(rows, title):
    print("")
    print(title)
    print("| id | version | issuedAt | expiry (+%dd) | days left | engine |" % TRUST_WINDOW_DAYS)
    print("|-----|---------|----------|--------------|-----------|--------|")
    for pid, ver, issued, expiry, left, engine in rows:
        print("| %s | %s | %s | %s | %s | %s |" % (pid, ver, issued, expiry, left, engine))


def main(argv=None):
    ap = argparse.ArgumentParser(description="Verify the deployed plugin distribution (read-only).")
    ap.add_argument("--index-url", default=DEFAULT_INDEX_URL,
                    help="deployed index URL (default: %(default)s)")
    ap.add_argument("--pin-source", default="dashboard/lib/plugin-keys.ts",
                    help="repo-relative file holding the committed PUBLIC pin")
    ap.add_argument("--mirror", default="",
                    help="directory to store the fetched bytes in (forensics)")
    ap.add_argument("--archive-dir", default="dashboard/plugins/_archive",
                    help="repo-relative rollback stock to verify for signature + trust "
                         "calendar (NOT required to be deployed: it lives outside "
                         "public/, so Vercel never serves it)")
    ap.add_argument("--archive-url", action="append", default=[],
                    help="extra archived manifest URL that MUST resolve (repeatable)")
    ap.add_argument("--trust-window-days", type=int, default=TRUST_WINDOW_DAYS)
    ap.add_argument("--warn-window-days", type=int, default=WARN_WINDOW_DAYS)
    args = ap.parse_args(argv)

    signer = load_signer()
    pin_b64, key_id = read_pin(args.pin_source)
    if pin_b64 != signer.DASH_PIN:
        raise SystemExit("pin drift: %s (%s) != sign-plugin.py DASH_PIN"
                         % (args.pin_source, key_id))
    try:
        pub = signer.load_public_key_b64(pin_b64)
    except Exception as ex:
        raise SystemExit("unusable public pin in %s: %s" % (args.pin_source, ex))

    index_url = args.index_url.strip()
    u = urlparse(index_url)
    if u.scheme != "https" or not u.hostname:
        raise SystemExit("--index-url must be an https URL (got %r)" % index_url)
    expected_host = u.hostname

    now = datetime.now(timezone.utc)
    problems = []
    rows = []

    print("deployed distribution: %s" % index_url)
    print("trust pin: %s (%s) from %s" % (pin_b64, key_id, args.pin_source))
    print("verified at: %s" % now.strftime("%Y-%m-%dT%H:%M:%SZ"))

    # ── index ────────────────────────────────────────────────────────────────
    status, body, err, redirects, effective = fetch(index_url, MAX_INDEX_BYTES)
    mirror_write(args.mirror, index_url, body)
    if status != 200:
        problems.append("index %s: HTTP %s%s" % (index_url, status, (" — " + err) if err else ""))
        print_table(rows, "| served (unavailable) |")
        return report(problems, 1)
    if redirects != "0":
        problems.append("index redirected (%s hops → %s); a 200 is required" % (redirects, effective))
    print("index: HTTP 200, %d bytes" % len(body))
    index = strict_json(signer, body, "index", problems)
    if index is None:
        return report(problems, 1)

    schema = index.get("schemaVersion")
    if schema is None:
        problems.append("index: schemaVersion missing")
    elif not isinstance(schema, int) or isinstance(schema, bool) or schema != SCHEMA_VERSION:
        problems.append("index: schemaVersion %r unsupported (app requires %d)"
                        % (schema, SCHEMA_VERSION))
    updated_at = index.get("updatedAt")
    if parse_instant(updated_at) is None:
        problems.append("index: updatedAt missing/unparseable (%r)" % (updated_at,))
    entries = index.get("entries")
    if not isinstance(entries, list) or not entries:
        problems.append("index: entries missing/empty")
        entries = []
    elif len(entries) > MAX_ENTRIES:
        problems.append("index: %d entries exceeds the %d cap" % (len(entries), MAX_ENTRIES))

    # ── every served manifest (+ sibling source.js for script payloads) ──────
    seen = set()
    archived_urls = list(args.archive_url)
    for e in entries:
        if not isinstance(e, dict):
            problems.append("index: entry is not an object")
            continue
        pid, ver = e.get("id"), e.get("version")
        if not isinstance(pid, str) or not ID_RE.match(pid or ""):
            problems.append("index: bad entry id %r" % (pid,))
            continue
        if pid in seen:
            problems.append("index: duplicate entry id %r (app keeps the first)" % pid)
            continue
        seen.add(pid)
        if not isinstance(ver, int) or isinstance(ver, bool) or ver < 1:
            problems.append("index: bad version for %s (%r)" % (pid, ver))
            continue
        min_app = e.get("minAppVersion")
        if not isinstance(min_app, str) or not APP_VERSION_RE.match(min_app):
            problems.append("index: bad minAppVersion for %s (%r)" % (pid, min_app))
        manifest_url = e.get("manifestUrl")
        if not isinstance(manifest_url, str) or len(manifest_url) > 512:
            problems.append("index: missing/oversized manifestUrl for %s" % pid)
            continue
        host_problem = host_ok(manifest_url.strip(), expected_host)
        if host_problem:
            problems.append("index: %s — %s" % (pid, host_problem))
            continue
        manifest_url = manifest_url.strip()
        if "/_archive/" in manifest_url:
            archived_urls.append(manifest_url)

        m_status, m_body, m_err, m_redirects, m_effective = fetch(manifest_url, MAX_MANIFEST_BYTES)
        mirror_write(args.mirror, manifest_url, m_body)
        label = "%s v%s" % (pid, ver)
        if m_status != 200:
            problems.append("%s manifest %s: HTTP %s%s"
                            % (label, manifest_url, m_status, (" — " + m_err) if m_err else ""))
            continue
        if m_redirects != "0":
            problems.append("%s manifest redirected (%s hops → %s); a 200 is required"
                            % (label, m_redirects, m_effective))
        engine, sha = verify_manifest(
            signer, pub, key_id, label, m_body.decode("utf-8", "replace"), pid, ver,
            args.trust_window_days, args.warn_window_days, now, problems, rows)
        kind = e.get("kind")
        if engine is not None and engine_skew(kind, engine):
            print("note: %s index kind=%r vs signed engine=%r (manifest wins, app logs only)"
                  % (label, kind, engine))
        if engine != "script":
            continue

        # Script payload: same host rule, 512 KiB cap, sha256 == scriptSha256.
        js_url = sibling_url(manifest_url, "source.js")
        js_problem = host_ok(js_url, expected_host)
        if js_problem:
            problems.append("%s source.js — %s" % (label, js_problem))
            continue
        j_status, j_body, j_err, j_redirects, j_effective = fetch(js_url, SCRIPT_MAX_BYTES)
        mirror_write(args.mirror, js_url, j_body)
        if j_status != 200:
            problems.append("%s source.js %s: HTTP %s%s"
                            % (label, js_url, j_status, (" — " + j_err) if j_err else ""))
            continue
        if j_redirects != "0":
            problems.append("%s source.js redirected (%s hops → %s); a 200 is required"
                            % (label, j_redirects, j_effective))
        actual = hashlib.sha256(j_body).hexdigest()
        if not isinstance(sha, str) or actual != sha:
            problems.append("%s source.js sha256 %s != scriptSha256 %r" % (label, actual, sha))

    # ── archived rollback stock ──────────────────────────────────────────────
    for url in archived_urls:
        host_problem = host_ok(url.strip(), expected_host)
        if host_problem:
            problems.append("archived manifest %s — %s" % (url, host_problem))
            continue
        a_status, a_body, a_err, _, _ = fetch(url.strip(), MAX_MANIFEST_BYTES)
        mirror_write(args.mirror, url, a_body)
        if a_status != 200:
            problems.append("archived manifest %s: HTTP %s%s"
                            % (url, a_status, (" — " + a_err) if a_err else ""))
            continue
        label = "archive %s" % urlparse(url).path
        engine, sha = verify_manifest(signer, pub, key_id, label,
                                      a_body.decode("utf-8", "replace"),
                                      None, None, args.trust_window_days,
                                      args.warn_window_days, now, problems, rows)
        if engine == "script":
            js_url = sibling_url(url.strip(), "source.js")
            j_status, j_body, j_err, _, _ = fetch(js_url, SCRIPT_MAX_BYTES)
            if j_status != 200:
                problems.append("%s source.js %s: HTTP %s%s"
                                % (label, js_url, j_status, (" — " + j_err) if j_err else ""))
                continue
            mirror_write(args.mirror, js_url, j_body)
            actual = hashlib.sha256(j_body).hexdigest()
            if not isinstance(sha, str) or actual != sha:
                problems.append("%s source.js sha256 %s != scriptSha256 %r"
                                % (label, actual, sha))

    archive_dir = os.path.join(REPO, args.archive_dir) if args.archive_dir else ""
    if archive_dir and os.path.isdir(archive_dir):
        print("")
        print("rollback stock (%s) — publisher-side, outside public/, NOT required to be "
              "deployed; signature + trust calendar only:" % args.archive_dir)
        for root, _dirs, files in os.walk(archive_dir):
            if "plugin.json" not in files:
                continue
            path = os.path.join(root, "plugin.json")
            rel = os.path.relpath(path, REPO)
            js_path = os.path.join(root, "source.js")
            verify_manifest(signer, pub, key_id, "archive %s" % rel,
                            open(path, encoding="utf-8").read(), None, None,
                            args.trust_window_days, args.warn_window_days,
                            now, problems, rows,
                            source_js_bytes=(open(js_path, "rb").read()
                                             if os.path.isfile(js_path) else None))
    elif archive_dir:
        print("note: rollback stock dir %s not present (nothing to verify)" % args.archive_dir)

    print_table(rows, "| served + archived payloads |")
    return report(problems, 0)


def report(problems, base_code):
    print("")
    if problems:
        print("VERIFY-DEPLOYED-PLUGINS FAIL (%d violation(s)):" % len(problems))
        for p in problems:
            print("  - %s" % p)
        return base_code or 1
    print("VERIFY-DEPLOYED-PLUGINS PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())