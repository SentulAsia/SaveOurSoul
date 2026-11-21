/**
 * Runtime configuration from environment variables. Three environments:
 *
 * | APP_ENV | Database                          | Where it runs                 |
 * |---------|-----------------------------------|-------------------------------|
 * | dev     | SQLite file (always)              | your laptop                   |
 * | qa      | PostgreSQL (Neon "qa" branch)     | Render "saveoursoul-api-qa"   |
 * | prod    | PostgreSQL (Neon "production")    | Render "saveoursoul-api"      |
 *
 * dev never connects to PostgreSQL, so a laptop cannot touch qa or prod data by accident.
 * qa and prod refuse to start with unsafe settings instead of running half-configured.
 */
export const Environments = ["dev", "qa", "prod"] as const;
export type Environment = (typeof Environments)[number];

export interface Config {
  environment: Environment;
  /** postgres://… (qa, prod) or a SQLite path (dev). */
  database: string;
  responderApiKey: string;
  enrollToken?: string;
  smsWebhookToken?: string;
  port: number;
}

export class ConfigError extends Error {}

const isPostgres = (s: string) => /^postgres(ql)?:\/\//.test(s);

export function loadConfig(env: NodeJS.ProcessEnv = process.env): { config: Config; warnings: string[] } {
  const environment = (env.APP_ENV ?? "dev") as Environment;
  if (!Environments.includes(environment)) throw new ConfigError(`APP_ENV must be one of ${Environments.join(", ")}`);
  const warnings: string[] = [];
  const databaseUrl = env.DATABASE_URL?.trim() ?? "";

  let database: string;
  if (environment === "dev") {
    database = env.DATABASE_PATH ?? "sos.db";
    if (databaseUrl) warnings.push("APP_ENV=dev always uses SQLite: DATABASE_URL is ignored");
  } else {
    // Render's disk is wiped on every deploy: a SQLite file there would silently lose every alert.
    if (!isPostgres(databaseUrl)) throw new ConfigError(`APP_ENV=${environment} needs DATABASE_URL set to a PostgreSQL URL`);
    database = databaseUrl;
  }

  const responderApiKey = env.RESPONDER_API_KEY ?? "";
  if (!responderApiKey) throw new ConfigError("RESPONDER_API_KEY must be set");
  if (environment !== "dev" && responderApiKey.length < 24) throw new ConfigError("RESPONDER_API_KEY must be at least 24 characters");

  const enrollToken = env.ENROLL_TOKEN || undefined;
  const smsWebhookToken = env.SMS_WEBHOOK_TOKEN || undefined;
  if (environment === "prod") {
    if (!enrollToken) warnings.push("ENROLL_TOKEN is not set: anyone can register devices");
    if (!smsWebhookToken) warnings.push("SMS_WEBHOOK_TOKEN is not set: anyone can post to the SMS webhook");
  }

  const config: Config = { environment, database, responderApiKey, port: Number(env.PORT ?? 8080) };
  if (enrollToken) config.enrollToken = enrollToken;
  if (smsWebhookToken) config.smsWebhookToken = smsWebhookToken;
  return { config, warnings };
}
