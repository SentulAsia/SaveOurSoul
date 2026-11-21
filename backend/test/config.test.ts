import assert from "node:assert/strict";
import { test } from "node:test";
import { ConfigError, loadConfig } from "../src/config.ts";

const PG = "postgresql://u:p@ep-x-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require";
const KEY = "k".repeat(32);

test("dev is the default and uses a local SQLite file", () => {
  const { config } = loadConfig({ RESPONDER_API_KEY: "dev" });
  assert.equal(config.environment, "dev");
  assert.equal(config.database, "sos.db");
  assert.equal(loadConfig({ RESPONDER_API_KEY: "dev", DATABASE_PATH: "/tmp/x.db" }).config.database, "/tmp/x.db");
});

test("dev never connects to PostgreSQL, even if DATABASE_URL is set", () => {
  const { config, warnings } = loadConfig({ APP_ENV: "dev", RESPONDER_API_KEY: "dev", DATABASE_URL: PG });
  assert.equal(config.database, "sos.db");
  assert.match(warnings[0]!, /ignored/);
});

test("qa and prod require a PostgreSQL DATABASE_URL and a strong responder key", () => {
  for (const env of ["qa", "prod"]) {
    assert.throws(() => loadConfig({ APP_ENV: env, RESPONDER_API_KEY: KEY }), ConfigError);
    assert.throws(() => loadConfig({ APP_ENV: env, RESPONDER_API_KEY: KEY, DATABASE_URL: "sos.db" }), /PostgreSQL/);
    assert.throws(() => loadConfig({ APP_ENV: env, RESPONDER_API_KEY: "short", DATABASE_URL: PG }), /24 characters/);
    assert.equal(loadConfig({ APP_ENV: env, RESPONDER_API_KEY: KEY, DATABASE_URL: PG }).config.database, PG);
  }
});

test("prod warns about open enrollment and an unprotected SMS webhook", () => {
  const { warnings } = loadConfig({ APP_ENV: "prod", RESPONDER_API_KEY: KEY, DATABASE_URL: PG });
  assert.equal(warnings.length, 2);
  const ok = loadConfig({ APP_ENV: "prod", RESPONDER_API_KEY: KEY, DATABASE_URL: PG, ENROLL_TOKEN: "e", SMS_WEBHOOK_TOKEN: "s" });
  assert.deepEqual(ok.warnings, []);
});

test("rejects unknown environments, including the old long names", () => {
  for (const name of ["staging", "test", "development", "testing", "production"]) {
    assert.throws(() => loadConfig({ APP_ENV: name, RESPONDER_API_KEY: KEY, DATABASE_URL: PG }), /APP_ENV/);
  }
});
