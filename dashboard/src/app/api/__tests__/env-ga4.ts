/**
 * Test-only side effect: the GA4 route captures `process.env.GA4_PROPERTY_ID`
 * at MODULE LOAD, so the value has to exist before the route module is
 * evaluated. Importing this file FIRST in the suite (ESM evaluates imports
 * in source order) sets it.
 */
process.env.GA4_PROPERTY_ID = "538347764";
