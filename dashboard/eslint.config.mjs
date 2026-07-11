// ESLint flat config — minimal until eslint-config-next supports flat config natively.
// ESLint is ignored during builds via next.config.ts; this config is for IDE linting only.
export default [
  { ignores: [".next/**", "out/**", "build/**", "next-env.d.ts"] },
];
