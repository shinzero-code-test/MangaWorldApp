# Release-note policy

**Scope:** the body of every GitHub Release that CI publishes for this app
(`.github/workflows/build.yml` → `Create Release` → `body:`).

**Status:** binding from the next release onward. A release whose body does not
satisfy this policy is an incomplete release — tag it, publish it, then fix the
body before the next tag.

---

## 1. Why the body is the release channel

Three facts decide the whole policy:

1. **The app is not on Google Play.** It is not intended to be. It ships as APK
   artifacts attached to **GitHub Releases**. There is no store listing, no
   "Update available" bubble, no staged rollout.
2. **There is no self-update.** The app cannot fetch and install a new build.
   Even the in-app update gate (Remote Config `min_supported_version` +
   `update_url`, landing with v9.8.0) only *tells* the user that a newer build
   exists and hands them a link. It never delivers the fix.
3. **The user population is small, real, and reachable exactly one way.** GA4
   shows the active fleet (see §5). People who read release notes on GitHub are
   the *minority*; the release body is the artifact that everyone is pointed at.

Therefore: **a release body that does not tell the user what to download, and
does not say whether their installed version is still supported, is a body that
cannot help anybody.** The AGENTS.md rule "every push & version bump creates a
release tag" is only half of the job — the body is the announcement.

---

## 2. The three mandatory elements

Every release body MUST contain all three, in this order, above the existing
"download table / supported sources / requirements" sections.

### 2.1 One line: what changed for users

One line, in plain Arabic, naming the **user-visible effect** — not the commit
list, not the internal refactor.

* Good: `تم إصلاح مشكلة عدم ظهور صور الفصول والأغلفة.`
* Bad: `refactor(core): unify ImageLoader providers` / `fixed 12 bugs`.

If a release genuinely changes nothing a user can see, say exactly that
(`لا تغييرات في هذه النسخة — تحديث اختياري.`). Silence is not an option; a
missing "what changed" is what makes people ignore the next one.

### 2.2 The minimum supported version

The body MUST name the version below which the app **must** be updated — the
exact same string that is published to Remote Config as
`min_supported_version`.

* Format: a bare version, e.g. `9.8.0`. No `v` prefix, no range.
* It must be stated as a **floor, with the consequence spelled out**:
  "النسخة `9.8.0` هي الحد الأدنى المدعوم — أي إصدار أقدم منه يجب تحديثه فوراً
  (broken images)."
* The string in the body and the RC value MUST be identical. A mismatch (the
  body says `9.8.0`, RC says `9.7.2`) is the classic failure of this policy:
  the user reads the body, believes they are supported, and is not.
* The version named in the body of release `X` is the floor that release `X`
  enforces. It may equal `X` (a floor that blocks the broken build) or be
  lower (a floor that only blocks something older) — but it must be stated
  every time, even when it does not change, so a reader can always tell
  whether their build is still supported.

### 2.3 The download link

The body MUST contain the direct link to the release assets, in addition to the
per-ABI table CI already generates.

* Link form: `https://github.com/<owner>/<repo>/releases/tag/<tag>` — the
  release page, where the APKs are attached. A link to the repository root is
  not acceptable; a link to the APK file directly is not acceptable either
  (the page is what a sideloading user can open on a phone).
* Keep the existing ABI table (`arm64-v8a` / `armeabi-v7a` / `x86_64` /
  `universal`) — most users do not know which one they need.
* Say plainly that the app is **not** on the Play Store, so nobody wastes time
  searching for it there.
* The in-app gate offers a "copy link" fallback for sideloading users who have
  no browser; the body must therefore carry a link that can be copied and
  pasted, not a button that only exists in the app.

---

## 3. The body template

Copy this into the `body:` of the `Create Release` step. The three mandatory
elements are the first three blocks; keep the rest as it is today.

```markdown
## 📦 MangaWorld <VERSION>

### ما الجديد للمستخدمين
<ONE LINE: the user-visible effect of this release — Arabic, plain.>

### الحد الأدنى المدعوم
النسخة <MIN_SUPPORTED_VERSION> هي الحد الأدنى المدعوم.
إذا كان إصدار جهازك أقدم من ذلك، فحدّث التطبيق الآن — الإصدارات الأقدم لا تعمل بشكل صحيح.

(minimum supported version: `<MIN_SUPPORTED_VERSION>`)

### التنزيل
التطبيق غير متوفر على متجر Google Play — التنزيل من GitHub Releases فقط.

رابط التحميل المباشر:
<RELEASE_URL>

اختر APK المناسب لجهازك:

| الجهاز | الملف |
|--------|-------|
| معظم الأجهزة الحديثة (64-bit) | `MangaWorld-release-arm64-v8a` |
| أجهزة قديمة (32-bit) | `MangaWorld-release-armeabi-v7a` |
| محاكي (x86_64) | `MangaWorld-release-x86_64` |
| جميع الأجهزة (universal) | `MangaWorld-release-universal` |

### المصادر المدعومة
… (the existing list)

### المتطلبات
- Android 8.0+ (API 26)
```

`MIN_SUPPORTED_VERSION` and `RELEASE_URL` are the two values that must be
filled in per release. Everything else is static.

---

## 4. When may `min_supported_version` be raised?

The floor is a one-way door for a user whose build is broken: the gate blocks
them, and if the download link does not work for them they are stuck. So
raising it needs evidence, and it happens on one of **two tracks**.

### Track A — emergency raise (a shipped build is broken for users)

Use when a version that is still in the field is broken (the current example:
builds before 9.7.x do not load covers or chapter images). Users will not
update on their own — the cohort can never reach the Track B threshold before
the gate pushes them, so waiting for Track B evidence here would mean never
raising it.

Requirements (all of them):

1. The fixing version is **published**, and its body satisfies §2 (link +
   minimum version + what changed) — the raise sends people to that page.
2. The Remote Config value is published **before** the release is announced.
3. The raise is announced in the release body of the fixing version (§2.2) and
   in the dashboard login notice (the admins see it first, and it documents the
   policy).
4. The floor stays **reversible**: `min_supported_version` is lowered again the
   moment the tracking shows the new build is the problem. Track A is a
   one-day action, not a policy.
5. The fleet is watched for 24 h after the raise (`fleet-report.mjs --days 1`,
   then `--days 7`), and any new incident is triaged before the raise is kept.

### Track B — permanent floor (decommissioning an old version)

Use when an old version is retired for good and no user should remain on it.

Requirements (all of them):

1. The candidate version `V` is published and its body names `V` as the floor.
2. The cohort evidence, measured with the fleet report (§5):
   - active users on `>= V` are **≥ 95 %** of all active users, **and**
   - active users on `>= V` are **≥ 30** in absolute terms (95 % of 3 users is
     noise, not evidence),
   - **over two consecutive 14-day windows** (run the report, wait 7 days, run
     it again — a single window can be a fluke of one enthusiastic updater).
3. No open incident attributable to `>= V` in that window
   (`RolledBack` / `Quarantined` / `Revoked` stay at their baseline).
4. The value in the release body and the RC value are identical (see §2.2).

### Rules that hold on both tracks

- **Never raise the floor to a version that has no published release.**
- **Never raise the floor above the latest published release.**
- **Never raise the floor on a build that only a handful of volunteers run.**
- **Lowering the floor is always allowed, immediately, and needs no evidence.**
  A raise that turns out to be wrong must be undone in hours, not days.
- **Never raise the floor in the same window as an unrelated large change.**
  One variable at a time, or the incident cannot be attributed.

---

## 5. How to measure the cohort

From `dashboard/`:

```bash
node scripts/fleet-report.mjs                 # 30-day window (default)
node scripts/fleet-report.mjs --days 14       # the soak window
node scripts/fleet-report.mjs --days 30 --strict   # exit 1 when the cohort gate fails
```

The report prints the cohort by `appVersion` (with a `telemetry` marker for the
builds that emit the `plugin_*` analytics events) plus the four soak queries:
sweep outcomes, incidents, script calls, trust rejections.

The credential is read from `FIREBASE_SERVICE_ACCOUNT` (the environment) or
from `dashboard/.env.local`. It is a **multi-line** service-account JSON — the
PEM private key contains literal newlines — so the parser reads it as a block;
a one-line read truncates the JSON and the report dies before it reaches GA4.
(A malformed credential fails with a readable message, never a stack trace.)

**Measured 2026-11-11** (`--days 30`):

| | |
|---|---|
| active users | 81 |
| on `>= 9.5.0` (emits `plugin_*` telemetry) | 56 |
| on `9.4.1` (before the telemetry build) | 18 |
| on `9.2.x` / `9.3.x` | 7 |
| on `>= 9.7.1` (contains the image fix) | 6 |
| incidents | 6 × `RolledBack` (no `plugin_id` recorded), 1 health transition (`azora`: OK → DEGRADED) |
| trust rejects | 0 |

**What this means today:** only 6 of 81 active users are on a build that
contains the image fix, so **no version currently satisfies the Track B
thresholds** — and none can, because the affected users do not update on their
own. That is the argument for Track A (raise now, lower it again if it goes
wrong, and let the fleet migrate), not a reason to skip the gate.

---

## 6. Pre-release checklist

Before tagging a release:

- [ ] The body names **one line** of user-visible change.
- [ ] The body names the **minimum supported version**, identical to the RC
      `min_supported_version` that will be published.
- [ ] The body carries the **release link** and says the app is not on Play.
- [ ] The floor being published is justified on **Track A** or **Track B**, and
      the evidence is written down (report output + date).
- [ ] If the floor changed: the fleet is watched for 24 h afterwards.
- [ ] The release tag exists and the APK artifacts are attached.

## 7. Where the pieces live

| What | Where |
|---|---|
| The body template CI publishes | `.github/workflows/build.yml` → `create-release` → `Create Release` → `body:` |
| The floor the app reads | Remote Config `min_supported_version` (+ `latest_version`, `update_url`) |
| The cohort measurement | `dashboard/scripts/fleet-report.mjs` (this directory) |
| The gate itself | plan item 1.1 in `tmp/plan/adversarial-fixes/p3-next.md` |
