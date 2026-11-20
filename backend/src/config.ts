/**
 * Runtime configuration from environment variables. Three environments:
 *
 * | APP_ENV       | Database                         | Typical host                    |
 * |---------------|----------------------------------|---------------------------------|
 * | development   | SQLite file, or DATABASE_URL      | laptop, or Render "dev" service |
 * | testing       | DATABASE_URL (Neon "testing")     | Render "testing" service        |
 * | production    | DATABASE_URL (Neon "production")  | Render "production" service     |
 *
 * Testing and production refuse to start with unsafe settings instead of running half-configured.
 */
export const Environments = ["development", "testing", "production"] as const;
export type Environment = (typeof Environments)[number];

export interface Config {
  environment: Environment;
  /** postgres://… or a SQLite path. */
  database: string;
  responderApiKey: string;
  enrollToken?: string;
  smsWebhookToken?: string;
  port: number;
}

export class ConfigError extends Error {}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): { config: Config; warnings: string[] } {
  const environment = (env.APP_ENV ?? "development") as Environment;
  if (!Environments.includes(environment)) throw new ConfigError(`APP_ENV must be one of ${Environments.join(", ")}`);
  const deployed = environment !== "development";
  const warnings: string[] = [];

  const database = env.DATABASE_URL?.trim() || (deployed ? "" : env.DATABASE_PATH ?? "sos.db");
  if (!database) throw new ConfigError(`DATABASE_URL is required when APP_ENV=${environment}`);
  if (deployed && !/^postgres(ql)?:\/\//.test(database)) {
    // Render's disk is wiped on every deploy: a SQLite file there silently loses every alert.
    throw new ConfigError(`APP_ENV=${environment} needs a PostgreSQL DATABASE_URL, not a SQLite file`);
  }

  const responderApiKey = env.RESPONDER_API_KEY ?? "";
  if (!responderApiKey) throw new ConfigError("RESPONDER_API_KEY must be set");
  if (deployed && responderApiKey.length < 24) throw new ConfigError("RESPONDER_API_KEY must be at least 24 characters");

  const enrollToken = env.ENROLL_TOKEN || undefined;
  const smsWebhookToken = env.SMS_WEBHOOK_TOKEN || undefined;
  if (environment === "production") {
    if (!enrollToken) warnings.push("ENROLL_TOKEN is not set: anyone can register devices");
    if (!smsWebhookToken) warnings.push("SMS_WEBHOOK_TOKEN is not set: anyone can post to the SMS webhook");
  }

  const config: Config = { environment, database, responderApiKey, port: Number(env.PORT ?? 8080) };
  if (enrollToken) config.enrollToken = enrollToken;
  if (smsWebhookToken) config.smsWebhookToken = smsWebhookToken;
  return { config, warnings };
}
