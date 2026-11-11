/**
 * Test-only side effect: the storage route captures the Cloudinary
 * credentials at MODULE LOAD, so they have to exist before the route
 * module is evaluated. Importing this file FIRST in the suite (ESM
 * evaluates imports in source order) sets them.
 */
process.env.CLOUDINARY_CLOUD_NAME = "test-cloud";
process.env.CLOUDINARY_API_KEY = "test-key";
process.env.CLOUDINARY_API_SECRET = "test-secret";
