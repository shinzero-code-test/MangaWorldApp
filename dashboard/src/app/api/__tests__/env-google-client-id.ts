/**
 * Test-only side effect: the Google-credential route captures
 * `process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID` at MODULE LOAD, so the value
 * has to exist before the route module is evaluated. Importing this file
 * FIRST in the suite (ESM evaluates imports in source order) sets it.
 */
process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID = "test-google-client-id.apps.googleusercontent.com";
