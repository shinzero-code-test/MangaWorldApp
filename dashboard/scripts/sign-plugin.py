#!/usr/bin/env python3
"""Offline plugin signer (human publisher tool; Phase 2B/3 publish flow).

Signs canonical `plugin.json` bytes with the `official-1` Ed25519 key and
verifies with the public pin before promoting. Usage:

  python3 sign-plugin.py --selftest
      Oracle check: re-canonicalizes the SHIPPED hijala/lavascans manifests and
      verifies their signatures with the public pin. Must pass before any
      signing — it proves byte-exact JCS agreement with the app verifier.
      (Also proves the local keypair is consistent: public == derived.)

  python3 sign-plugin.py --manifest <unsigned plugin.json> --js <source.js|none>
      --key <PRIVATE_KEY.b64> --out <signed plugin.json>
      Computes scriptSha256 (when --js is given), injects it, JCS-canonicalizes
      minus `signature`, signs, writes the signed manifest, then RE-VERIFIES
      the output with the public pin (dashboard/lib/plugin-keys.ts value).

JCS (RFC 8785) notes: object keys sorted by UTF-16 code units (= code-point
order for all inputs here, incl. astral chars whose surrogates are monotonic);
duplicate keys are a hard error (strict parse); numbers are integer-exact with
ECMAScript-corrected float fallback (manifest numerics are ints in practice).
Requires: python3 + `cryptography` package (Ed25519).
"""
import base64
import hashlib
import json
import sys

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
        point = mant.index(".") if "." in mant else len(mant)
        pos = point + exp
        if pos <= 0:
            return "0." + "0" * (-pos) + digits
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


# ---------------------------------------------------------------- commands

import os
REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
DASH_PIN = "o01gRyLfjV9Zxuo8rOxYB/kdMvPt9mLHbv/tKN9k9FM="


def cmd_selftest():
    pub = load_public_key_b64(DASH_PIN)
    # 1. shipped pilots verify (oracle: our JCS == the original signer's bytes)
    for pid in ("hijala", "lavascans", "manonga"):
        path = os.path.join(REPO, "dashboard/public/plugins/%s/v1/plugin.json" % pid)
        text = open(path, encoding="utf-8").read()
        node = json.loads(text, object_pairs_hook=_no_dupes)
        key_id, sig = parse_sig_header(node["signature"])
        assert key_id == "official-1", key_id
        verify_signature(pub, canonical_manifest_bytes(text), sig)
        print("oracle %s: signature VERIFIES (JCS byte-exact)" % pid)
    # 2. tamper fails
    text = open(os.path.join(REPO, "dashboard/public/plugins/hijala/v1/plugin.json"),
                encoding="utf-8").read()
    node = json.loads(text, object_pairs_hook=_no_dupes)
    _, sig = parse_sig_header(node["signature"])
    bad = canonical_manifest_bytes(text) + b" "
    try:
        verify_signature(pub, bad, sig)
    except Exception:
        print("tamper check: modified bytes REJECTED as expected")
    else:
        raise SystemExit("tamper check FAILED to reject")
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
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
        derived = priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        stored = base64.b64decode(open(pub_path).read().strip())
        assert derived == stored, "local keypair mismatch"
        print("keypair check: private derives the pinned public key")
    print("SELFTEST PASS")


def cmd_sign(manifest_path, js_path, key_path, out_path):
    import os
    text = open(manifest_path, encoding="utf-8").read()
    node = json.loads(text, object_pairs_hook=_no_dupes)
    if js_path != "none":
        js = open(js_path, "rb").read()
        if len(js) > 512 * 1024:
            raise SystemExit("source.js exceeds 512 KiB cap")
        node["scriptSha256"] = hashlib.sha256(js).hexdigest()
        print("scriptSha256:", node["scriptSha256"])
    # re-serialize WITHOUT signature for canonicalization (placeholder dropped)
    node.pop("signature", None)
    unsigned = json.dumps(node, ensure_ascii=False)
    canonical = canonical_json_bytes(unsigned)
    priv = load_private_key(key_path)
    sig = base64.b64encode(priv.sign(canonical)).decode()
    node["signature"] = "ed25519:official-1:" + sig
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(node, f, ensure_ascii=False, indent=2)
        f.write("\n")
    # verify-before-promote with the PUBLIC pin (never trust the just-signed bytes)
    pub = load_public_key_b64(DASH_PIN)
    # Verify-before-promote through the FILE (not the in-memory bytes):
    # re-parse the written artifact, drop signature, JCS, verify with pin.
    signed_text = open(out_path, encoding="utf-8").read()
    check = json.loads(signed_text, object_pairs_hook=_no_dupes)
    _, sig2 = parse_sig_header(check["signature"])
    verify_signature(pub, canonical_manifest_bytes(signed_text), sig2)
    print("signed + independently verified:", out_path)


def cmd_check_published():
    """CI gate for dashboard-only edits (3.5): index schema + manifest URLs,
    per-entry signature verification against the pin, scriptSha256 agreement,
    and pinned-key equality across the three copies (signer/dashboard/app).
    Exits non-zero with the first violation named."""
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
        mp = os.path.join(REPO, "dashboard/public/plugins/%s/v%d/plugin.json" % (pid, ver))
        if not os.path.isfile(mp):
            fail("missing promoted manifest %s" % mp)
        text = open(mp, encoding="utf-8").read()
        node = json.loads(text, object_pairs_hook=_no_dupes)
        key_id, sig = parse_sig_header(node.get("signature", ""))
        if key_id != "official-1":
            fail("unexpected key id for %s" % pid)
        try:
            verify_signature(pub, canonical_manifest_bytes(text), sig)
        except Exception as ex:
            fail("signature mismatch for %s (%s)" % (pid, ex))
        if node.get("id") != pid or node.get("version") != ver:
            fail("id/version mismatch for %s" % pid)
        engine = node.get("engine", "")
        if engine == "script":
            pin = node.get("scriptSha256", "")
            jsp = os.path.join(REPO, "dashboard/public/plugins/%s/v%d/source.js" % (pid, ver))
            if not os.path.isfile(jsp):
                fail("missing source.js for %s" % pid)
            js = open(jsp, "rb").read()
            if len(js) > 512 * 1024:
                fail("source.js exceeds cap for %s" % pid)
            if hashlib.sha256(js).hexdigest() != pin.lower():
                fail("scriptSha256 mismatch for %s" % pid)
        print("published %s v%d OK" % (pid, ver))
    print("CHECK-PUBLISHED PASS")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--selftest"]:
        cmd_selftest()
    elif args == ["--check-published"]:
        cmd_check_published()
    elif len(args) == 8 and args[0] == "--manifest" and args[2] == "--js" and args[4] == "--key" and args[6] == "--out":
        cmd_sign(args[1], args[3], args[5], args[7])
    else:
        print(__doc__)
        sys.exit(2)
