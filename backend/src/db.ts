import { DatabaseSync } from "node:sqlite";
import pg from "pg";

/**
 * A tiny async SQL layer over two engines:
 *  - SQLite (node:sqlite) for APP_ENV=dev (your computer) and the default test run;
 *  - PostgreSQL (e.g. Neon) for APP_ENV=test and APP_ENV=prod on Render.
 *
 * Queries are written once with `?` placeholders in the common subset of both dialects.
 */
export type Dialect = "sqlite" | "postgres";
export type Param = string | number | bigint | Buffer | null;
export type Row = Record<string, unknown>;

export interface Db {
  readonly dialect: Dialect;
  all(sql: string, params?: Param[]): Promise<Row[]>;
  get(sql: string, params?: Param[]): Promise<Row | undefined>;
  /** Returns the number of rows changed. */
  run(sql: string, params?: Param[]): Promise<number>;
  /** Executes several statements without parameters (schema setup). */
  exec(sql: string): Promise<void>;
  transaction<T>(fn: (tx: Db) => Promise<T>): Promise<T>;
  ping(): Promise<void>;
  close(): Promise<void>;
}

// ---------------------------------------------------------------------------------------------
// SQLite

class SqliteDb implements Db {
  readonly dialect = "sqlite" as const;
  private readonly db: DatabaseSync;
  /** node:sqlite is synchronous, but transactions span awaits: serialise them. */
  private queue: Promise<unknown> = Promise.resolve();

  constructor(path: string) {
    this.db = new DatabaseSync(path);
    this.db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
  }

  async all(sql: string, params: Param[] = []): Promise<Row[]> {
    return this.db.prepare(sql).all(...params) as Row[];
  }

  async get(sql: string, params: Param[] = []): Promise<Row | undefined> {
    return this.db.prepare(sql).get(...params) as Row | undefined;
  }

  async run(sql: string, params: Param[] = []): Promise<number> {
    return Number(this.db.prepare(sql).run(...params).changes);
  }

  async exec(sql: string): Promise<void> {
    this.db.exec(sql);
  }

  transaction<T>(fn: (tx: Db) => Promise<T>): Promise<T> {
    const next = this.queue.then(async () => {
      this.db.exec("BEGIN IMMEDIATE");
      try {
        const out = await fn(this);
        this.db.exec("COMMIT");
        return out;
      } catch (e) {
        this.db.exec("ROLLBACK");
        throw e;
      }
    });
    this.queue = next.catch(() => undefined);
    return next;
  }

  async ping(): Promise<void> {
    this.db.prepare("SELECT 1").get();
  }

  async close(): Promise<void> {
    this.db.close();
  }
}

// ---------------------------------------------------------------------------------------------
// PostgreSQL

// BIGINT (unix timestamps) would otherwise come back as strings.
pg.types.setTypeParser(pg.types.builtins.INT8, (v) => Number(v));

/** Rewrites `?` placeholders to `$1, $2, …` (our SQL never contains a literal `?`). */
function toPg(sql: string): string {
  let i = 0;
  return sql.replace(/\?/g, () => `$${++i}`);
}

type Queryable = pg.Pool | pg.PoolClient;

class PgDb implements Db {
  readonly dialect = "postgres" as const;
  private readonly q: Queryable;
  private readonly pool: pg.Pool | null;

  constructor(q: Queryable, pool: pg.Pool | null) {
    this.q = q;
    this.pool = pool;
  }

  async all(sql: string, params: Param[] = []): Promise<Row[]> {
    return (await this.q.query(toPg(sql), params)).rows as Row[];
  }

  async get(sql: string, params: Param[] = []): Promise<Row | undefined> {
    return (await this.all(sql, params))[0];
  }

  async run(sql: string, params: Param[] = []): Promise<number> {
    return (await this.q.query(toPg(sql), params)).rowCount ?? 0;
  }

  async exec(sql: string): Promise<void> {
    await this.q.query(sql);
  }

  async transaction<T>(fn: (tx: Db) => Promise<T>): Promise<T> {
    if (!this.pool) return fn(this); // already inside a transaction
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const out = await fn(new PgDb(client, null));
      await client.query("COMMIT");
      return out;
    } catch (e) {
      await client.query("ROLLBACK").catch(() => undefined);
      throw e;
    } finally {
      client.release();
    }
  }

  async ping(): Promise<void> {
    await this.q.query("SELECT 1");
  }

  async close(): Promise<void> {
    await this.pool?.end();
  }
}

/** `postgres://…` / `postgresql://…` → PostgreSQL; anything else is a SQLite file path (or ":memory:"). */
export function openDb(target: string): Db {
  if (/^postgres(ql)?:\/\//.test(target)) {
    const pool = new pg.Pool({
      connectionString: target,
      max: Number(process.env.DATABASE_POOL_MAX ?? 10),
      // Neon scales compute to zero when idle; the first connection after that takes a moment.
      connectionTimeoutMillis: 15_000,
      idleTimeoutMillis: 30_000,
    });
    // An idle client losing its connection (e.g. Neon suspending) must not crash the process.
    pool.on("error", (e) => console.warn("postgres pool error:", e.message));
    return new PgDb(pool, pool);
  }
  return new SqliteDb(target);
}
