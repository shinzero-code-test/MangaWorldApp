#!/usr/bin/env python3
"""Offline plugin signer + distribution gate (human publisher tool; Phase 2B/3).

Signs canonical `plugin.json` bytes with the `official-1` Ed25519 key, validates
them BEFORE anything reaches the publish path, promotes an already-signed staged
payload, and gates what is already published. Usage:

  python3 sign-plugin.py --selftest
      Oracle check: re-canonicalizes every manifest named by
      `public/plugins/index.json` and verifies its signature with the public pin,
      plus a tamper-detection round on the first index entry. Must pass before any
      signing — it proves byte-exact JCS agreement with the app verifier, and it
      follows the index so a version bump never turns CI red for the wrong reason.
      (Also proves the local keypair is consistent: public == derived.)

  python3 sign-plugin.py --check-published
      CI gate: index schema + manifest URLs, per-entry signature verification
      against the pin, scriptSha256 agreement, trust-anchor expiry (audit C4), and
      the same pin/hash checks for the archived rollback stock under `_archive/`.

  python3 sign-plugin.py --manifest <unsigned plugin.json> --js <source.js|none>
      --key <PRIVATE_KEY.b64> --out <signed plugin.json>
      Computes scriptSha256 (when --js is given), injects it, JCS-canonicalizes
      minus `signature`, VALIDATES (schema, engine<->--js, future issuedAt, hash
      agreement, self-sign on the exact bytes to be written) and only then writes
      + independently re-verifies the output with the public pin
      (dashboard/lib/plugin-keys.ts value). A failed gate writes nothing.

  python3 sign-plugin.py --promote <id> [--dry-run]
      Lands an already-signed staged payload in the publish path: copies
      `dashboard/plugins/_incoming/<id>/plugin.json` (+ `source.js` for script
      kinds) into `dashboard/public/plugins/<id>/v<version>/` after re-verifying
      its signature with the pin read from `dashboard/lib/plugin-keys.ts`. Refuses
      to overwrite an existing version dir — versions are immutable on device, so a
      correction is a new version, never a re-sign. `index.json` is NOT edited;
      the bump is a reviewed, separate step.

JCS (RFC 8785) notes: object keys sorted by UTF-16 code units (= code-point
order for all inputs here, incl. astral chars whose surrogates are monotonic);
duplicate keys are a hard error (strict parse); numbers are integer-exact with
ECMAScript-corrected float fallback (manifest numerics are ints in practice).
Requires: python3 + `cryptography` package (Ed25519).
"""
import base64
import hashlib
import json
import os
import re
import shutil
import sys
from datetime import datetime, timedelta, timezone

# ---------------------------------------------------------------- JCS (RFC 8785)

_ESCAPES = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f",
            "\n": "\\n", "\r": "\\r", "\t": "\\t"}


def _jcs_str(s):
    out = ['"']
    for ch in s:
        if ch in _ESCAPES:
            out.append(_ESCAPES[ch])
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def _jcs_num(n):
    if isinstance(n, bool):
        raise ValueError("bool is not a JSON number here")
    if isinstance(n, int):
        return str(n)
    if isinstance(n, float):
        import math
        if math.isnan(n) or math.isinf(n):
            raise ValueError("non-finite number")
        r = repr(n)
        if "e" not in r and "E" not in r:
            return r
        # ECMAScript shortest-form correction for exponent spellings.
        mant, exp = re_split_exp(r)
        exp = int(exp)
        digits = mant.replace(".", "")
        point = mant.index(".") if "." in mant else len(digits)
        pos = point + exp
        if pos <= 0:
            return "0" + "0" * (-pos) + digits
        if pos >= len(digits):
            return digits + "0" * (pos - len(digits))
        return digits[:pos] + "." + digits[pos:]
    raise ValueError("bad number %r" % (n,))


def re_split_exp(r):
    r = r.replace("E", "e")
    i = r.index("e")
    return r[:i], r[i + 1:]


def _jcs(node):
    if node is None:
        return "null"
    if node is True:
        return "true"
    if node is False:
        return "false"
    if isinstance(node, str):
        return _jcs_str(node)
    if isinstance(node, (int, float)):
        return _jcs_num(node)
    if isinstance(node, list):
        return "[" + ",".join(_jcs(x) for x in node) + "]"
    if isinstance(node, dict):
        # dict preserves insertion order; sort by UTF-16 code units.
        # Python str order == UTF-16 unit order (surrogate math is monotonic).
        items = sorted(node.items(), key=lambda kv: _utf16_units(kv[0]))
        return "{" + ",".join(_jcs_str(k) + ":" + _jcs(v) for k, v in items) + "}"
    raise ValueError("bad node %r" % type(node))


def _utf16_units(s):
    out = []
    for ch in s:
        o = ord(ch)
        if o < 0x10000:
            out.append(o)
        else:
            o -= 0x10000
            out.append(0xD800 + (o >> 10))
            out.append(0xDC00 + (o & 0x3FF))
    return out


def _no_dupes(pairs):
    obj = {}
    for k, v in pairs:
        if k in obj:
            raise ValueError("duplicate key: %r" % k)
        obj[k] = v
    return obj


def canonical_json_bytes(text):
    """Strict-parse (duplicate-key rejecting) then JCS-canonicalize to bytes."""
    node = json.loads(text, object_pairs_hook=_no_dupes)
    return _jcs(node).encode("utf-8")


def canonical_manifest_bytes(manifest_text):
    """Canonical bytes for signing: strict-parse, drop `signature`, JCS."""
    node = json.loads(manifest_text, object_pairs_hook=_no_dupes)
    if not isinstance(node, dict) or "signature" not in node:
        raise ValueError("manifest must be an object with a signature field")
    del node["signature"]
    return _jcs(node).encode("utf-8")


# ---------------------------------------------------------------- Ed25519

def load_private_key(path):
    from cryptography.hazmat.primitives.asymmetric import ed25519
    seed = base64.b64decode(open(path).read().strip())
    if len(seed) != 32:
        raise ValueError("private key must decode to 32 bytes")
    return ed25519.Ed25519PrivateKey.from_private_bytes(seed)


def load_public_key_b64(b64):
    from cryptography.hazmat.primitives.asymmetric import ed25519
    raw = base64.b64decode(b64.strip())
    if len(raw) != 32:
        raise ValueError("public key must decode to 32 bytes")
    return ed25519.Ed25519PublicKey.from_public_bytes(raw)


def verify_signature(public_key, canonical: bytes, sig_b64: str):
    public_key.verify(base64.b64decode(sig_b64), canonical)


def parse_sig_header(header):
    parts = header.split(":")
    if len(parts) != 3 or parts[0] != "ed25519":
        raise ValueError("bad signature shape")
    return parts[1], parts[2]


# ---------------------------------------------------------------- constants

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
DASH_PIN = "o01gRyLfjV9Zxuo8rOxYB/kdMvPt9mLHbv/tKN9k9FM="
SIGNING_KEY_ID = "official-1"

# Mirrors of the device-side constants (ManifestParser.kt / PluginTrust.kt /
# ScriptContract.kt). WHY duplicated here: this tool is the last cheap place to
# refuse a payload the app would refuse — after signing, the only remaining
# reviewer is a device that has already downloaded it.
MAX_TRUST_AGE_DAYS = 90            # PluginTrust.MAX_TRUST_AGE_DAYS
EXPIRY_WARN_DAYS = 30              # audit C4: fail this long BEFORE an anchor dies
CLOCK_SKEW_SECONDS = 600           # issuedAt may be a hair ahead (publisher's clock)
SCRIPT_MAX_BYTES = 512 * 1024      # ScriptContract.SCRIPT_MAX_BYTES
MAX_MANIFEST_BYTES = 64 * 1024     # ManifestParser.MAX_MANIFEST_BYTES
MAX_HOSTS = 16
MIN_TIMEOUT_MS, MAX_TIMEOUT_MS = 1_000, 120_000
MIN_RESPONSE_MB, MAX_RESPONSE_MB = 1, 50

ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")               # ManifestParser.ID_REGEX
APP_VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")                 # APP_VERSION_REGEX
SHA256_HEX_RE = re.compile(r"^[0-9a-fA-F]{64}$")                # SHA256_HEX_REGEX
LOCALE_KEY_RE = re.compile(r"^[A-Za-z]{2,8}(-[A-Za-z]{2,8})?$")
HOST_ENTRY_RE = re.compile(r"^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
                           r"(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")  # HostPolicy
VERSION_DIR_RE = re.compile(r"^v(\d+)$")
PIN_CONST_RE = re.compile(r"PLUGIN_SIGNING_PUBLIC_KEY_B64\s*=\s*\"([A-Za-z0-9+/=]+)\"")

# Closed engine vocabulary (SourceEngine). `custom` is builtin-only (Phase 3), so
# it is deliberately absent: signing one would produce a payload the app rejects.
REMOTE_ENGINES = ("madara", "mangareader", "astro", "api", "script")

KNOWN_FIELDS = frozenset((
    "id", "version", "minAppVersion", "issuedAt", "bridgeApi", "names", "logo",
    "engine", "engineApi", "baseUrl", "requiresVerification", "enabledByDefault",
    "requiresPermission", "config", "paths", "allowedHosts", "timeoutMs",
    "maxResponseMb", "scriptSha256", "signature",
))

DIST_DIR = "dashboard/public/plugins"
STAGING_DIR = "dashboard/plugins/_incoming"
# Rollback stock. `_archive/README.md` documents `_archive/<id>/v<version>/`;
# it currently lives OUTSIDE `public/` (so it is not served). Both roots are
# scanned: an archive under `public/` would be reachable over HTTP, which is
# exactly what the gate should look at twice.
ARCHIVE_ROOTS = ("dashboard/plugins/_archive", "dashboard/public/plugins/_archive")

# Sentinel the staging manifests ship with (see _incoming/README.md): replaced by
# the computed pin at sign time. Any other non-hex value is a hard refusal.
STAGING_PIN_PLACEHOLDER = "COMPUTE_AT_PROMOTE_SEE_README"


def refuse(msg):
    """Fail closed with a named reason. Every gate runs BEFORE any write."""
    raise SystemExit("sign-plugin REFUSED: " + msg)


def rel(path):
    """Repo-relative path for messages — absolute sandbox/tmp paths are noise."""
    try:
        return os.path.relpath(path, REPO)
    except ValueError:      # different drive on Windows; keep whatever we have
        return path


# ---------------------------------------------------------------- time / trust calendar

def now_utc():
    return datetime.now(timezone.utc)


def parse_instant(value, what="issuedAt"):
    """Strict ISO-8601 instant parse. WHY strict: PluginTrust.isFresh fails closed
    on an unparseable anchor, so a typo here publishes a payload that no device can
    ever install."""
    if not isinstance(value, str):
        refuse("%s must be a string (got %r)" % (what, value))
    try:
        dt = datetime.fromisoformat(
            value[:-1] + "+00:00" if value.endswith("Z") else value)
    except ValueError:
        refuse("%s is not a valid ISO-8601 instant: %r" % (what, value))
    if dt.tzinfo is None:
        refuse("%s must carry a UTC offset (got %r)" % (what, value))
    return dt.astimezone(timezone.utc)


def anchor_expiry(issued_at):
    """issuedAt + MAX_TRUST_AGE_DAYS.

    WHY a shared helper: the device applies this window in
    `PluginStore.verify(enforceFreshness = true)` -> `PluginTrust.isFresh`, and
    audit C4 is that nobody was tracking it — one calendar, computed the same way
    on both sides, is what makes `--check-published` a real gate.
    """
    return issued_at + timedelta(days=MAX_TRUST_AGE_DAYS)


def days_left(expiry, now=None):
    """Whole days until expiry (floored, so "expires in <24h" reads 0)."""
    return int((expiry - (now or now_utc())).total_seconds() // 86400)


def anchor_row(pid, ver, kind, issued_iso, expiry, left):
    """One line of the trust calendar printed by --check-published."""
    return {
        "id": pid, "version": ver, "kind": kind, "issued": issued_iso,
        "expiry": expiry.strftime("%Y-%m-%dT%H:%M:%SZ"), "days": left,
        "status": "EXPIRED" if left < 0 else ("EXPIRING" if left <= EXPIRY_WARN_DAYS else "ok"),
    }


def print_anchor_table(title, rows):
    if not rows:
        print("\n%s: none" % title)
        return
    print("\n%s" % title)
    print("  %-14s %-4s %-11s %-21s %-21s %5s  %s"
          % ("id", "ver", "kind", "issuedAt", "expires (+%dd)" % MAX_TRUST_AGE_DAYS,
             "days", "status"))
    for r in rows:
        print("  %-14s %-4s %-11s %-21s %-21s %5d  %s"
              % (r["id"], r["version"], r["kind"], r["issued"], r["expiry"],
                 r["days"], r["status"]))


# ---------------------------------------------------------------- reading / verifying payloads

def parse_strict(text, where):
    """Strict parse of manifest text (duplicate keys rejected, like the app)."""
    try:
        node = json.loads(text, object_pairs_hook=_no_dupes)
    except ValueError as ex:
        refuse("%s: strict parse failed (%s)" % (where, ex))
    if not isinstance(node, dict):
        refuse("%s: top level must be an object" % where)
    return node


def read_manifest(path, where):
    try:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    except OSError as ex:
        refuse("%s: cannot read %s (%s)" % (where, rel(path), ex.strerror or ex))
    return text, parse_strict(text, where)


def sha256_file(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def verify_payload(manifest_path, pub, where):
    """Signature + identity verification of an on-disk payload, shared by
    `--check-published`, `--promote` and the write gate.

    Deliberately NOT the full v1 schema: read-only gates must not turn red when the
    APP grows a field (that is the audit-C7 lesson — CI failing for a reason that
    has nothing to do with the bytes under review). Authoring gates
    (`cmd_sign`) do run the schema.
    """
    text, node = read_manifest(manifest_path, where)
    pid, ver = node.get("id"), node.get("version")
    if not isinstance(pid, str) or not ID_RE.fullmatch(pid):
        refuse("%s: bad id %r" % (where, pid))
    if isinstance(ver, bool) or not isinstance(ver, int) or ver < 1:
        refuse("%s: bad version %r (expected int >= 1)" % (where, ver))
    header = node.get("signature")
    if not isinstance(header, str):
        refuse("%s: no signature field" % where)
    try:
        key_id, sig = parse_sig_header(header)
        canonical = canonical_manifest_bytes(text)
    except ValueError as ex:
        refuse("%s: %s" % (where, ex))
    if key_id != SIGNING_KEY_ID:
        refuse("%s: unexpected key id %r (expected %r)" % (where, key_id, SIGNING_KEY_ID))
    try:
        verify_signature(pub, canonical, sig)
    except Exception as ex:
        refuse("%s: signature mismatch for %s v%s (%s)" % (where, pid, ver, ex))
    return node, canonical


def check_script_pin(node, payload_dir, where):
    """scriptSha256 <-> source.js agreement. WHY both directions: the app refuses
    a script manifest without pinned bytes AND refuses an unexpected script
    payload on a non-script manifest (`ManifestParser`), so a promoted payload can
    never end up half-pinned."""
    engine, pin = node.get("engine"), node.get("scriptSha256")
    jsp = os.path.join(payload_dir, "source.js")
    if engine == "script":
        if not isinstance(pin, str) or not SHA256_HEX_RE.fullmatch(pin):
            refuse("%s: script requires a 64-hex scriptSha256 (got %r)" % (where, pin))
        if not os.path.isfile(jsp):
            refuse("%s: script payload is missing %s" % (where, rel(jsp)))
        if os.path.getsize(jsp) > SCRIPT_MAX_BYTES:
            refuse("%s: source.js exceeds the %d byte cap" % (where, SCRIPT_MAX_BYTES))
        actual = sha256_file(jsp)
        if actual != pin.lower():
            refuse("%s: scriptSha256 mismatch (manifest pins %s, source.js hashes to %s)"
                   % (where, pin.lower(), actual))
        return actual
    if pin is not None:
        refuse("%s: scriptSha256 is script-only (engine=%r)" % (where, engine))
    if os.path.exists(jsp):
        refuse("%s: source.js present but engine is %r — refusing to move unpayloaded bytes" % (where, engine))
    return None


def dashboard_pin_b64():
    """The pin exactly as the dashboard declares it. Parsed (not hardcoded twice)
    so `--promote` verifies with the same constant the app pins; None when the
    file is unreadable, which the caller reports instead of failing silently."""
    try:
        with open(os.path.join(REPO, "dashboard/lib/plugin-keys.ts"), encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return None
    m = PIN_CONST_RE.search(text)
    return m.group(1) if m else None


# ---------------------------------------------------------------- authoring schema gate

def validate_manifest_schema(node, where):
    """Mirror of the device-side `ManifestParser` v1 schema, fail closed. Returns
    the parsed `issuedAt` so the caller can apply the trust clock.

    WHY: the app only discovers these on DOWNLOAD — after `vercel --prod`, after
    every device has been offered the payload, and with no way to un-ring the
    bell. Typo'd field names (`engin`, `base_uri`) and bad ranges are silent here
    today.
    """
    if not isinstance(node, dict):
        refuse("%s: top level must be an object" % where)
    unknown = sorted(k for k in node if k not in KNOWN_FIELDS)
    if unknown:
        refuse("%s: unknown field(s) %s — the app fails closed on these (app schema v1)"
               % (where, ", ".join(repr(u) for u in unknown)))
    pid = node.get("id")
    if not isinstance(pid, str) or not ID_RE.fullmatch(pid):
        refuse("%s: bad id %r (expected [a-z0-9][a-z0-9_-]{0,63})" % (where, pid))
    ver = node.get("version")
    if isinstance(ver, bool) or not isinstance(ver, int) or ver < 1:
        refuse("%s: bad version %r (expected int >= 1)" % (where, ver))
    mav = node.get("minAppVersion")
    if not isinstance(mav, str) or not APP_VERSION_RE.fullmatch(mav):
        refuse("%s: bad minAppVersion %r (expected MAJOR.MINOR.PATCH)" % (where, mav))
    issued = parse_instant(node.get("issuedAt"), "%s: issuedAt" % where)
    engine = node.get("engine")
    if engine not in REMOTE_ENGINES:
        refuse("%s: unknown engine %r (closed set: %s; 'custom' is builtin-only)"
               % (where, engine, ", ".join(REMOTE_ENGINES)))
    bridge = node.get("bridgeApi")
    if engine == "script":
        if isinstance(bridge, bool) or not isinstance(bridge, int) or bridge < 1:
            refuse("%s: script requires bridgeApi >= 1" % where)
    elif bridge is not None:
        refuse("%s: bridgeApi is script-only" % where)
    # Mirror of ManifestParser's "scriptSha256 is script-only": a leftover pin on a
    # theme-engine manifest is refused on device, so never mint a signature over one.
    # (For `script` the pin is injected/validated by the caller, which owns the bytes.)
    if engine != "script" and node.get("scriptSha256") is not None:
        refuse("%s: scriptSha256 is script-only (engine=%r)" % (where, engine))
    engine_api = node.get("engineApi")
    if isinstance(engine_api, bool) or not isinstance(engine_api, int) or engine_api < 1:
        refuse("%s: bad engineApi %r (expected int >= 1)" % (where, engine_api))
    if not isinstance(node.get("baseUrl"), str) or not node["baseUrl"]:
        refuse("%s: missing baseUrl" % where)
    for flag in ("requiresVerification", "enabledByDefault"):
        if not isinstance(node.get(flag), bool):
            refuse("%s: %s must be a boolean" % (where, flag))
    perm = node.get("requiresPermission")
    if perm is not None and not isinstance(perm, bool):
        refuse("%s: requiresPermission must be a boolean" % where)
    names = node.get("names")
    if not isinstance(names, dict) or not names:
        refuse("%s: missing/empty names" % where)
    for locale, label in names.items():
        if not LOCALE_KEY_RE.fullmatch(locale) or not isinstance(label, str) \
                or not label.strip() or len(label) > 100:
            refuse("%s: bad names entry %r" % (where, locale))
    hosts = node.get("allowedHosts")
    if not isinstance(hosts, list) or not hosts or len(hosts) > MAX_HOSTS:
        refuse("%s: bad allowedHosts size (expected 1..%d)" % (where, MAX_HOSTS))
    for host in hosts:
        if not isinstance(host, str) or len(host) > 253 or any(
                bad in host for bad in "*/:@") or any(ch.isspace() for ch in host) \
                or not HOST_ENTRY_RE.fullmatch(host.lower()):
            refuse("%s: bad allowedHosts entry %r (literal hostnames only)" % (where, host))
    timeout = node.get("timeoutMs")
    if isinstance(timeout, bool) or not isinstance(timeout, int) \
            or not MIN_TIMEOUT_MS <= timeout <= MAX_TIMEOUT_MS:
        refuse("%s: bad timeoutMs %r (expected %d..%d)"
               % (where, timeout, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS))
    resp = node.get("maxResponseMb")
    if isinstance(resp, bool) or not isinstance(resp, int) \
            or not MIN_RESPONSE_MB <= resp <= MAX_RESPONSE_MB:
        refuse("%s: bad maxResponseMb %r (expected %d..%d)"
               % (where, resp, MIN_RESPONSE_MB, MAX_RESPONSE_MB))
    return issued


# ---------------------------------------------------------------- commands

def cmd_selftest():
    pub = load_public_key_b64(DASH_PIN)
    # 1. promoted manifests verify (oracle: our JCS == the original signer's
    # bytes). Follows the index (versions move; v1 lives in _archive).
    index = json.loads(
        open(os.path.join(REPO, "dashboard/public/plugins/index.json"), encoding="utf-8").read(),
        object_pairs_hook=_no_dupes,
    )
    entries = index.get("entries")
    if not isinstance(entries, list) or not entries:
        raise SystemExit("selftest FAILED: index.json has no entries")
    published = []
    for e in entries:
        pid, ver = e["id"], e["version"]
        path = os.path.join(REPO, "dashboard/public/plugins/%s/v%d/plugin.json" % (pid, ver))
        text = open(path, encoding="utf-8").read()
        node = json.loads(text, object_pairs_hook=_no_dupes)
        key_id, sig = parse_sig_header(node["signature"])
        assert key_id == SIGNING_KEY_ID, key_id
        canonical = canonical_manifest_bytes(text)
        verify_signature(pub, canonical, sig)
        # 1b. canonicalization is stable/idempotent on the shipped bytes — the
        # property the device relies on when it re-canonicalizes a download.
        assert canonical_json_bytes(canonical.decode("utf-8")) == canonical, \
            "canonicalization not idempotent for %s v%d" % (pid, ver)
        published.append((pid, path, text, sig))
        print("oracle %s: signature VERIFIES (JCS byte-exact)" % pid)
    # 2. tamper fails — on a REAL published entry taken from the index (never a
    # hardcoded id/version: the first hijala bump or retirement used to turn CI
    # red for a file that no longer exists).
    pid, path, text, sig = published[0]
    bad = canonical_manifest_bytes(text) + b" "
    try:
        verify_signature(pub, bad, sig)
    except Exception:
        print("tamper check (%s): modified bytes REJECTED as expected" % pid)
    else:
        raise SystemExit("tamper check FAILED to reject")
    # 2b. a flipped field inside the manifest is also rejected (the realistic
    # tamper: an edited payload re-serialized with the old signature).
    node = json.loads(text, object_pairs_hook=_no_dupes)
    node["baseUrl"] = "https://evil.example"
    try:
        verify_signature(pub, canonical_json_bytes(json.dumps(node, ensure_ascii=False)), sig)
    except Exception:
        print("tamper check (%s): edited field REJECTED as expected" % pid)
    else:
        raise SystemExit("field tamper check FAILED to reject")
    # 3. duplicate keys rejected before canonicalization
    try:
        canonical_json_bytes('{"a":1,"a":2}')
    except ValueError as e:
        print("duplicate-key check: rejected (%s)" % e)
    else:
        raise SystemExit("duplicate check FAILED")
    # 4. local keypair consistency (no secrets printed)
    seed_path = os.path.join(REPO, "tmp/secrets/plugin-signing-official-1/PRIVATE_KEY.b64")
    pub_path = os.path.join(REPO, "tmp/secrets/plugin-signing-official-1/PUBLIC_KEY.b64")
    if os.path.exists(seed_path):
        priv = load_private_key(seed_path)
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
        derived = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        # The backup public half must be PRESENT for this check to mean anything;
        # a half-present custody dir must skip, not crash with a traceback.
        if not os.path.exists(pub_path):
            print("keypair check: SKIPPED (no PUBLIC_KEY.b64 next to the private key)")
        else:
            stored = base64.b64decode(open(pub_path).read().strip())
            assert derived == stored, "local keypair mismatch"
            assert derived == base64.b64decode(DASH_PIN), "local keypair != pinned dashboard key"
            print("keypair check: private derives the pinned public key")
    print("SELFTEST PASS")


def cmd_sign(manifest_path, js_path, key_path, out_path):
    text = open(manifest_path, encoding="utf-8").read()
    node = parse_strict(text, "manifest")
    has_js = js_path != "none"
    js = None
    if has_js:
        js = open(js_path, "rb").read()
        if len(js) > SCRIPT_MAX_BYTES:
            raise SystemExit("source.js exceeds 512 KiB cap")

    # ── validate-before-write (audit C9). Every gate below runs BEFORE the out
    # file is touched: an invalid manifest that reaches the publish path is only
    # caught on device, after `vercel --prod`, with no way to un-ring the bell.
    issued = validate_manifest_schema(node, "manifest")

    # Gate 1 — engine <-> --js pairing. A descriptor signed with --js gets a
    # scriptSha256 the app rejects; a script signed without --js never pins its
    # bytes. Both were only ever caught by ManifestParser on a real device.
    engine = node["engine"]
    if engine == "script" and not has_js:
        refuse("engine 'script' requires --js <source.js> — a script manifest without "
               "pinned bytes is refused on install")
    if engine != "script" and has_js:
        refuse("--js given but engine is %r — scriptSha256 is script-only" % (engine,))

    # Gate 2 — issuedAt may not be in the future. PluginTrust.isFresh rejects
    # `anchor > now`, so a clock-skewed publisher would ship a payload that
    # verifies in CI and installs nowhere.
    now = now_utc()
    if issued > now + timedelta(seconds=CLOCK_SKEW_SECONDS):
        refuse("issuedAt %s is in the future (now %s, %ds tolerance) — PluginTrust.isFresh "
               "rejects an anchor after now, so every device would refuse this payload"
               % (node["issuedAt"], now.strftime("%Y-%m-%dT%H:%M:%SZ"), CLOCK_SKEW_SECONDS))

    # Gate 3 — the declared scriptSha256 must agree with the bytes we are about
    # to pin. A staged manifest may carry the documented placeholder (replaced
    # here, as the publish flow has always done) but NEVER a pin that disagrees
    # with the actual source.js: that is drift between two files a reviewer would
    # have had to catch by eye.
    if has_js:
        actual = hashlib.sha256(js).hexdigest()
        declared = node.get("scriptSha256")
        if declared is None:
            print("scriptSha256: absent in the manifest, injecting the computed pin")
        elif isinstance(declared, str) and SHA256_HEX_RE.fullmatch(declared):
            if declared.lower() != actual:
                refuse("scriptSha256 mismatch: manifest pins %s but --js bytes hash to %s — "
                       "refusing to sign a payload whose pin and bytes disagree"
                       % (declared.lower(), actual))
        elif declared == STAGING_PIN_PLACEHOLDER:
            print("scriptSha256: staging placeholder replaced with the computed pin")
        else:
            refuse("scriptSha256 %r is neither a 64-hex pin nor the documented staging "
                   "placeholder %r" % (declared, STAGING_PIN_PLACEHOLDER))
        node["scriptSha256"] = actual
        print("scriptSha256:", node["scriptSha256"])

    # re-serialize WITHOUT signature for canonicalization (placeholder dropped)
    node.pop("signature", None)
    unsigned = json.dumps(node, ensure_ascii=False)
    canonical = canonical_json_bytes(unsigned)
    priv = load_private_key(key_path)
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    derived_pub = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    # Gate 4 — the key we sign with must be the key the devices pin. A mismatch is
    # either a wrong --key file or an undocumented rotation; either way the bytes
    # would be distributed signed by a key no APK trusts.
    if derived_pub != base64.b64decode(DASH_PIN):
        refuse("private key does not derive the pinned %s public key — a rotated key must "
               "be pinned in PluginTrust.kt + plugin-keys.ts + this tool first"
               % SIGNING_KEY_ID)
    sig = base64.b64encode(priv.sign(canonical)).decode()
    node["signature"] = "ed25519:%s:%s" % (SIGNING_KEY_ID, sig)

    # Gate 5 — self-check the EXACT bytes about to be written (never the in-memory
    # dict): strict re-parse, canonical bytes identical to what we signed, JCS
    # idempotent, signature verifies under the matching (derived) public key.
    payload = json.dumps(node, ensure_ascii=False, indent=2) + "\n"
    if len(payload.encode("utf-8")) > MAX_MANIFEST_BYTES:
        refuse("signed manifest exceeds the %d byte app cap" % MAX_MANIFEST_BYTES)
    written_node = parse_strict(payload, "signed manifest")
    written_canonical = canonical_manifest_bytes(payload)
    if written_canonical != canonical:
        refuse("canonicalization changed the bytes after signing (serialization is not stable)")
    if canonical_json_bytes(written_canonical.decode("utf-8")) != written_canonical:
        refuse("canonicalization is not idempotent — the app would re-canonicalize differently")
    written_key, written_sig = parse_sig_header(written_node["signature"])
    if written_key != SIGNING_KEY_ID:
        refuse("signature header names %r, expected %r" % (written_key, SIGNING_KEY_ID))
    try:
        verify_signature(load_public_key_b64(base64.b64encode(derived_pub).decode()),
                         written_canonical, written_sig)
    except Exception as ex:
        refuse("self-check failed: the bytes about to be written do not verify (%s)" % ex)

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(payload)
    # verify-before-promote with the PUBLIC pin (never trust the just-signed bytes)
    pub = load_public_key_b64(DASH_PIN)
    # Verify-before-promote through the FILE (not the in-memory bytes):
    # re-parse the written artifact, drop signature, JCS, verify with pin.
    signed_text = open(out_path, encoding="utf-8").read()
    check = parse_strict(signed_text, "written manifest")
    _, sig2 = parse_sig_header(check["signature"])
    verify_signature(pub, canonical_manifest_bytes(signed_text), sig2)
    print("signed + independently verified:", out_path)


def resolve_staging_dir(pid):
    """Staging is FLAT today (`_incoming/<id>/`, which `ScriptCanaryTest` hardcodes);
    `_incoming/<id>/v<version>/` is accepted too because the README documents it."""
    base = os.path.join(REPO, STAGING_DIR, pid)
    if os.path.isfile(os.path.join(base, "plugin.json")):
        return base
    if os.path.isdir(base):
        versioned = sorted(
            d for d in os.listdir(base)
            if VERSION_DIR_RE.match(d) and os.path.isfile(os.path.join(base, d, "plugin.json"))
        )
        if len(versioned) == 1:
            return os.path.join(base, versioned[0])
        if len(versioned) > 1:
            refuse("ambiguous staging: %s contains %s — keep ONE candidate there"
                   % (os.path.join(STAGING_DIR, pid), ", ".join(versioned)))
    refuse("no staged payload for '%s' (expected %s/%s/plugin.json)"
           % (pid, STAGING_DIR, pid))


def cmd_promote(pid, dry_run=False):
    """Land an ALREADY-SIGNED staged payload in the publish path.

    WHY promote is a separate action from sign: signing is where the human
    decides what is trusted, promoting is where bytes become reachable by every
    device — and the second must be able to refuse a payload the first produced
    under a stale pin, a stale key or an incomplete stage.

    Staging fixtures (`fixtures/`, `harness.mjs`, `AUDIT.md`) stay put: they are
    the review evidence, and `ScriptCanaryTest` asserts staging<->public BYTE
    equality, so consuming the staged payload would turn the Kotlin gate red.
    """
    if not ID_RE.fullmatch(pid or ""):
        refuse("bad source id %r (expected [a-z0-9][a-z0-9_-]{0,63})" % (pid,))
    src = resolve_staging_dir(pid)
    pin = dashboard_pin_b64()
    if pin is None:
        print("note: dashboard/lib/plugin-keys.ts unreadable — signature checked with this "
              "tool's pin only")
    elif pin != DASH_PIN:
        refuse("dashboard pin drift: lib/plugin-keys.ts pins %s but this tool pins %s"
               % (pin, DASH_PIN))
    node, _canonical = verify_payload(os.path.join(src, "plugin.json"),
                                      load_public_key_b64(pin or DASH_PIN), "staged payload")
    # The manifest's own id must be the source we were asked to promote: promoting
    # `<other-id>`'s bytes into `public/plugins/<pid>/v<n>/` would make the index
    # name a source the payload does not claim (id is immutable and == stored
    # sourceId on device — see _incoming/README.md).
    if node["id"] != pid:
        refuse("staged manifest declares id %r but it is staged as %r — ids are immutable; "
               "re-stage under the right directory" % (node["id"], pid))
    ver = node["version"]

    # Both directions of the script rule, against the STAGED bytes.
    check_script_pin(node, src, "staged payload")

    dst_dir = os.path.join(REPO, DIST_DIR, pid, "v%d" % ver)
    if os.path.exists(dst_dir):
        refuse("target %s already exists — versions are immutable (the app refuses a "
               "same-version re-sign as 'version immutable: bytes differ'); ship v%d instead"
               % (rel(dst_dir), ver + 1))

    moves = [("plugin.json", os.path.join(src, "plugin.json"), os.path.join(dst_dir, "plugin.json"))]
    if node.get("engine") == "script":
        moves.append(("source.js", os.path.join(src, "source.js"), os.path.join(dst_dir, "source.js")))

    print("promote %s v%d: %s -> %s%s"
          % (pid, ver, rel(src), rel(dst_dir),
             "  [DRY RUN — nothing written]" if dry_run else ""))
    for label, s, d in moves:
        print("  %-12s %s  ->  %s" % (label, rel(s), rel(d)))
    if dry_run:
        print("next: bump %s/%s (version + updatedAt), archive the superseded version, "
              "then `vercel --prod`" % (DIST_DIR, "index.json"))
        return

    os.makedirs(dst_dir, exist_ok=False)
    for label, s, d in moves:
        shutil.copy2(s, d)
        # Read-back comparison, mirroring the app's activation post-condition
        # (v9.3.0: a record can never claim a version whose bytes are absent).
        if sha256_file(s) != sha256_file(d):
            refuse("%s did not land byte-identical at %s — remove the partial %s"
                   % (label, rel(d), rel(dst_dir)))
    # Re-verify the PROMOTED file from disk, not the staged object we just read.
    promoted, _ = verify_payload(os.path.join(dst_dir, "plugin.json"),
                                 load_public_key_b64(pin or DASH_PIN), "promoted payload")
    check_script_pin(promoted, dst_dir, "promoted payload")
    print("promoted %s v%d: signature re-verified from disk" % (pid, promoted["version"]))
    print("next: bump %s/index.json (version + updatedAt), archive the superseded version "
          "under %s, then `vercel --prod`" % (DIST_DIR, "dashboard/plugins/_archive"))


def scan_archive(pub, published_hashes):
    """Verify the archived rollback stock with the same pin/hash checks as the
    published payloads.

    WHY (audit C4/C10-d): `_archive/` is the documented one-click rollback target
    and NO gate looked at it. The stock could rot — a pruned `source.js`, a drifted
    hash, a signature that no longer pins, a version directory that disagrees with
    its manifest — and the failure would only surface during a rollback, when the
    least amount of time exists to fix it.
    """
    rows, issues, seen = [], [], {}
    for rel_root in ARCHIVE_ROOTS:
        root = os.path.join(REPO, rel_root)
        if not os.path.isdir(root):
            continue
        for dirpath, _dirs, files in os.walk(root):
            if "plugin.json" not in files:
                continue
            mpath = os.path.join(dirpath, "plugin.json")
            rel_path = rel(mpath)
            try:
                node, _ = verify_payload(mpath, pub, "archived %s" % rel_path)
                pid, ver = node["id"], node["version"]
                vdir = VERSION_DIR_RE.match(os.path.basename(dirpath))
                if vdir and int(vdir.group(1)) != ver:
                    refuse("archived %s: manifest version %d does not match directory '%s'"
                           % (rel_path, ver, os.path.basename(dirpath)))
                check_script_pin(node, dirpath, "archived %s" % rel_path)
                issued = parse_instant(node.get("issuedAt"), "archived %s: issuedAt" % rel_path)
            except SystemExit as ex:
                issues.append(str(ex))
                continue
            key = (pid, ver)
            if key in seen:
                issues.append("duplicate archived %s v%d (%s and %s)"
                              % (pid, ver, seen[key], rel_path))
            seen[key] = rel_path
            live = published_hashes.get(key)
            if live is not None and live != sha256_file(mpath):
                issues.append("archived %s: %s v%d is ALSO published with different bytes "
                              "(versions are immutable)" % (rel_path, pid, ver))
            rows.append(anchor_row(pid, ver, "archived", node["issuedAt"],
                                   anchor_expiry(issued), days_left(anchor_expiry(issued))))
            print("archived %s v%d OK" % (pid, ver))
    return rows, issues


def cmd_check_published():
    """CI gate for dashboard-only edits (3.5): index schema + manifest URLs,
    per-entry signature verification against the pin, scriptSha256 agreement,
    pinned-key equality across the three copies (signer/dashboard/app), the
    trust-anchor expiry calendar (audit C4), and the same checks for archived
    rollback stock. Exits non-zero with the first violation named."""
    from urllib.parse import urlparse

    def fail(msg):
        raise SystemExit("check-published FAILED: " + msg)

    pub = load_public_key_b64(DASH_PIN)
    # 4.3: the same base64 pin must live in all three files (substring
    # presence, not first-match — those files contain other base64).
    for label, rel in (("PluginTrust.kt",
                        "app/src/main/kotlin/com/exapps/mangaworld/core/source/plugins/PluginTrust.kt"),
                       ("plugin-keys.ts", "dashboard/lib/plugin-keys.ts")):
        text = open(os.path.join(REPO, rel), encoding="utf-8").read()
        if DASH_PIN not in text:
            fail("pinned key drift in %s" % label)
    print("key pins agree across signer/dashboard/app")

    index_path = os.path.join(REPO, "dashboard/public/plugins/index.json")
    index = json.loads(open(index_path, encoding="utf-8").read(), object_pairs_hook=_no_dupes)
    if index.get("schemaVersion") != 1:
        fail("index schemaVersion != 1")
    entries = index.get("entries")
    if not isinstance(entries, list) or not entries:
        fail("index entries missing/empty")
    if len(entries) > 1000:
        fail("index exceeds entry cap")
    index_host = urlparse("https://mangaworld-admin.vercel.app/plugins/index.json").hostname
    seen = set()
    published_hashes = {}
    rows = []
    for e in entries:
        if not isinstance(e, dict):
            fail("index entry not an object")
        pid = e.get("id")
        if not isinstance(pid, str) or not pid or pid in seen:
            fail("bad/duplicate index id %r" % (pid,))
        seen.add(pid)
        ver = e.get("version")
        if not isinstance(ver, int) or ver < 1:
            fail("bad version for %s" % pid)
        if e.get("kind") not in ("descriptor", "script"):
            fail("bad kind for %s" % pid)
        murl = e.get("manifestUrl", "")
        u = urlparse(murl)
        if u.scheme != "https" or (u.hostname or "").lower() != index_host:
            fail("manifestUrl off-distribution-host for %s" % pid)
        payload_dir = os.path.join(REPO, "dashboard/public/plugins/%s/v%d" % (pid, ver))
        mp = os.path.join(payload_dir, "plugin.json")
        if not os.path.isfile(mp):
            fail("missing promoted manifest %s" % mp)
        text, node = read_manifest(mp, "published %s v%d" % (pid, ver))
        key_id, sig = parse_sig_header(node.get("signature", ""))
        if key_id != SIGNING_KEY_ID:
            fail("unexpected key id for %s" % pid)
        try:
            verify_signature(pub, canonical_manifest_bytes(text), sig)
        except Exception as ex:
            fail("signature mismatch for %s (%s)" % (pid, ex))
        if node.get("id") != pid or node.get("version") != ver:
            fail("id/version mismatch for %s" % pid)
        engine = node.get("engine", "")
        # index `kind` must agree with the manifest engine, and vice versa —
        # otherwise the app's install path and the index's copy of the truth
        # describe different payloads.
        expect_kind = "script" if engine == "script" else "descriptor"
        if e.get("kind") != expect_kind:
            fail("index kind %r disagrees with manifest engine %r for %s"
                 % (e.get("kind"), engine, pid))
        try:
            check_script_pin(node, payload_dir, "published %s v%d" % (pid, ver))
        except SystemExit as ex:
            fail(str(ex).replace("sign-plugin REFUSED: ", ""))
        issued = parse_instant(node.get("issuedAt"), "published %s v%d: issuedAt" % (pid, ver))
        expiry = anchor_expiry(issued)
        rows.append(anchor_row(pid, ver, e.get("kind"), node["issuedAt"], expiry, days_left(expiry)))
        published_hashes[(pid, ver)] = sha256_file(mp)
        print("published %s v%d OK" % (pid, ver))

    archived_rows, archive_issues = scan_archive(pub, published_hashes)
    print_anchor_table("trust-anchor calendar — published", rows)
    print_anchor_table("trust-anchor calendar — archived (rollback stock)", archived_rows)
    print("\ntrust window: issuedAt + %d days (PluginTrust.MAX_TRUST_AGE_DAYS); "
          "a payload is refused on download once the anchor expires"
          % MAX_TRUST_AGE_DAYS)
    if archive_issues:
        for i in archive_issues:
            print("  ! " + i)
        fail("; ".join(archive_issues))
    # Aggregate the expiry findings instead of exiting on the first one, so the
    # tables above are printed before the gate fails: the whole calendar is the
    # actionable artifact, and same-version bytes are immutable, so an operator
    # needs every date at once (re-issue = new issuedAt + VERSION BUMP + re-sign).
    expiring = [r for r in rows + archived_rows if r["days"] <= EXPIRY_WARN_DAYS]
    if expiring:
        for r in expiring:
            print("  ! %s v%s: issuedAt %s expires %s (%d days left) — re-issue now"
                  % (r["id"], r["version"], r["issued"], r["expiry"], r["days"]))
        fail("%d trust anchor(s) within %d days of expiry (anchor = issuedAt + %d days); "
             "re-issue = new issuedAt + version bump + re-sign, never a same-version re-sign"
             % (len(expiring), EXPIRY_WARN_DAYS, MAX_TRUST_AGE_DAYS))
    print("CHECK-PUBLISHED PASS")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--selftest"]:
        cmd_selftest()
    elif args == ["--check-published"]:
        cmd_check_published()
    elif len(args) >= 2 and args[0] == "--promote" and set(args[2:]) <= {"--dry-run"}:
        cmd_promote(args[1], dry_run="--dry-run" in args)
    elif len(args) == 8 and args[0] == "--manifest" and args[2] == "--js" and args[4] == "--key" and args[6] == "--out":
        cmd_sign(args[1], args[3], args[5], args[7])
    else:
        print(__doc__)
        sys.exit(2)