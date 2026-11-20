import assert from "node:assert/strict";
import { test } from "node:test";
import { ConfigError, loadConfig } from "../src/config.ts";

const PG = "postgresql://u:p@ep-x-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require";
const KEY = "k".repeat(32);

test("development falls back to a local SQLite file", () => {
  const { config } = loadConfig({ RESPONDER_API_KEY: "dev" });
  assert.equal(config.environment, "development");
  assert.equal(config.database, "sos.db");
});

test("testing and production require a PostgreSQL DATABASE_URL and a strong responder key", () => {
  for (const env of ["testing", "production"]) {
    assert.throws(() => loadConfig({ APP_ENV: env, RESPONDER_API_KEY: KEY }), ConfigError);
    assert.throws(() => loadConfig({ APP_ENV: env, RESPONDER_API_KEY: KEY, DATABASE_URL: "sos.db" }), /PostgreSQL/);
    assert.throws(() => loadConfig({ APP_ENV: env, RESPONDER_API_KEY: "short", DATABASE_URL: PG }), /24 characters/);
    assert.equal(loadConfig({ APP_ENV: env, RESPONDER_API_KEY: KEY, DATABASE_URL: PG }).config.database, PG);
  }
});

test("production warns about open enrollment and an unprotected SMS webhook", () => {
  const { warnings } = loadConfig({ APP_ENV: "production", RESPONDER_API_KEY: KEY, DATABASE_URL: PG });
  assert.equal(warnings.length, 2);
  const ok = loadConfig({ APP_ENV: "production", RESPONDER_API_KEY: KEY, DATABASE_URL: PG, ENROLL_TOKEN: "e", SMS_WEBHOOK_TOKEN: "s" });
  assert.deepEqual(ok.warnings, []);
});

test("rejects unknown environments", () => {
  assert.throws(() => loadConfig({ APP_ENV: "staging", RESPONDER_API_KEY: KEY }), /APP_ENV/);
});
