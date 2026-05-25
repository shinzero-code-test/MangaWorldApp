# Firebase 3.0 Rollout Playbook

## Shipping Scope

- Analytics events: `manga_viewed`, `chapter_read`, `search_query`, `download_status`
- User properties: `preferred_theme`, `reading_mode`, `notification_mode`, `reader_engagement_tier`, `home_layout_variant`
- Remote Config controls: scraper timeouts, retries, home layout variants, ML feature flags
- App quality: Crashlytics source/network keys, Coil image traces, sync traces, scraper network metrics
- Messaging: token registration, rich media notifications, App Distribution-ready CI
- ML Kit: page translation, smart reply suggestions, local cover auto-tagging
- Security: App Check client initialization with Play Integrity in release and debug provider in debug builds

## ML Kit Notes

- Smart Reply only returns suggestions for supported English conversations.
- The current page-translation implementation uses on-device OCR plus translation and may return no text for unsupported scripts or highly stylized panels.

## Firebase Console Tasks

### Audiences

- `Avid Readers`
  - include users where `reader_engagement_tier == avid`
  - optionally require `chapter_read` in the last 7 days
- `Inactive Users`
  - include users with notification permission enabled
  - exclude users with `chapter_read` in the last 7 days
  - optionally require `search_query` or `manga_viewed` in the last 30 days

### Remote Config / A-B Tests

- `home_layout_variant`
  - control: `default`
  - variant A: `latest_grid`
  - variant B: `latest_grid_trending_first`
- `scraper_connect_timeout_seconds`
- `scraper_read_timeout_seconds`
- `scraper_write_timeout_seconds`
- `scraper_retry_count`
- `ml_translation_enabled`
- `ml_smart_reply_enabled`
- `ml_cover_tagging_enabled`

### App Check

- Enable App Check for Firestore and Realtime Database with Play Integrity for the Android app in the Firebase console.
- For local QA builds, register the debug App Check token emitted by the app before enforcing App Check.

## GitHub Actions Secrets

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`
- `FIREBASE_SERVICE_ACCOUNT`
- `FIREBASE_APP_DISTRIBUTION_GROUPS` (optional, defaults to `internal-testers`)

## Release Flow

1. Push changes to the tracked branch.
2. Create and push the `3.0.0` tag.
3. Watch the `Build MangaWorld APK` workflow until:
   - lint/tests pass
   - release artifacts upload
   - Firebase App Distribution upload finishes
   - GitHub Release publishes for the tag
