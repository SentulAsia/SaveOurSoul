import type { Db, Dialect, Param, Row } from "./db.ts";

export type AlertStatus = "open" | "acknowledged" | "resolved" | "cancelled";

export interface Device {
  id: string;
  secret: Buffer;
  name: string;
  phone: string | null;
  createdAt: number;
}

export interface Alert {
  id: string;
  deviceId: string;
  deviceName: string | null;
  devicePhone: string | null;
  kind: string;
  status: AlertStatus;
  isTest: boolean;
  triggeredAt: number;
  firstReceivedAt: number;
  updatedAt: number;
  location: { lat: number; lon: number; accuracyM: number | null; fixAt: number } | null;
  batteryPct: number | null;
  channels: string[];
  acknowledgedBy: string | null;
  acknowledgedAt: number | null;
  resolvedAt: number | null;
  note: string | null;
}

export interface SignalRow {
  frameId: string;
  alertId: string;
  deviceId: string;
  flags: number;
  kind: number;
  hops: number;
  deviceTs: number;
  latE7: number | null;
  lonE7: number | null;
  accuracyM: number | null;
  batteryPct: number | null;
}

export interface Receipt {
  frameId: string;
  channel: string;
  relayDeviceId: string | null;
  smsFrom: string | null;
  hops: number;
  receivedAt: number;
}

/**
 * Versioned schema migrations. Append new versions; never edit a released one.
 * Written once for both dialects: only the binary and big-integer column types differ.
 */
function migrations(d: Dialect): string[] {
  const BLOB = d === "postgres" ? "BYTEA" : "BLOB";
  const TS = d === "postgres" ? "BIGINT" : "INTEGER"; // unix seconds
  return [
    `
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY,
      secret ${BLOB} NOT NULL,
      name TEXT NOT NULL,
      phone TEXT,
      created_at ${TS} NOT NULL
    );
    CREATE TABLE IF NOT EXISTS alerts (
      id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(id),
      kind TEXT NOT NULL,
      status TEXT NOT NULL,
      is_test INTEGER NOT NULL DEFAULT 0,
      triggered_at ${TS} NOT NULL,
      first_received_at ${TS} NOT NULL,
      updated_at ${TS} NOT NULL,
      lat_e7 INTEGER, lon_e7 INTEGER, accuracy_m INTEGER, fix_at ${TS},
      battery_pct INTEGER,
      acknowledged_by TEXT, acknowledged_at ${TS}, resolved_at ${TS}, note TEXT
    );
    CREATE INDEX IF NOT EXISTS alerts_status ON alerts(status, updated_at);
    CREATE TABLE IF NOT EXISTS signals (
      frame_id TEXT PRIMARY KEY,
      alert_id TEXT NOT NULL REFERENCES alerts(id),
      device_id TEXT NOT NULL,
      flags INTEGER NOT NULL,
      kind INTEGER NOT NULL,
      hops INTEGER NOT NULL,
      device_ts ${TS} NOT NULL,
      lat_e7 INTEGER, lon_e7 INTEGER, accuracy_m INTEGER, battery_pct INTEGER
    );
    CREATE INDEX IF NOT EXISTS signals_alert ON signals(alert_id, device_ts);
    CREATE TABLE IF NOT EXISTS receipts (
      frame_id TEXT NOT NULL REFERENCES signals(frame_id),
      channel TEXT NOT NULL,
      relay_device_id TEXT,
      sms_from TEXT,
      hops INTEGER NOT NULL,
      received_at ${TS} NOT NULL
    );
    CREATE INDEX IF NOT EXISTS receipts_frame ON receipts(frame_id);
    `,
  ];
}

export class Store {
  readonly db: Db;

  constructor(db: Db) {
    this.db = db;
  }

  /** Applies pending migrations. Safe to run from several instances at once on Postgres. */
  async migrate(): Promise<number> {
    const TS = this.db.dialect === "postgres" ? "BIGINT" : "INTEGER";
    await this.db.exec(`CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at ${TS} NOT NULL)`);
    const steps = migrations(this.db.dialect);
    return this.db.transaction(async (tx) => {
      if (tx.dialect === "postgres") await tx.get("SELECT pg_advisory_xact_lock(50115011)");
      const row = await tx.get("SELECT MAX(version) AS v FROM schema_migrations");
      const current = Number(row?.v ?? 0);
      for (let v = current + 1; v <= steps.length; v++) {
        await tx.exec(steps[v - 1]!);
        await tx.run("INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)", [v, Math.floor(Date.now() / 1000)]);
      }
      return steps.length;
    });
  }

  async close(): Promise<void> {
    await this.db.close();
  }

  /** Runs `fn` with a Store bound to one transaction. */
  transaction<T>(fn: (s: Store) => Promise<T>): Promise<T> {
    return this.db.transaction((tx) => fn(new Store(tx)));
  }

  // --- devices -------------------------------------------------------------

  async insertDevice(d: Device): Promise<void> {
    await this.db.run("INSERT INTO devices (id, secret, name, phone, created_at) VALUES (?, ?, ?, ?, ?)", [
      d.id, d.secret, d.name, d.phone, d.createdAt,
    ]);
  }

  async getDevice(id: string): Promise<Device | null> {
    const r = await this.db.get("SELECT * FROM devices WHERE id = ?", [id]);
    if (!r) return null;
    return {
      id: r.id as string,
      secret: Buffer.from(r.secret as Uint8Array),
      name: r.name as string,
      phone: (r.phone as string | null) ?? null,
      createdAt: Number(r.created_at),
    };
  }

  // --- signals ---------------------------------------------------------------

  /** Returns false if the frame was already stored (concurrent uploads of the same frame are normal). */
  async insertSignal(s: SignalRow): Promise<boolean> {
    const n = await this.db.run(
      `INSERT INTO signals (frame_id, alert_id, device_id, flags, kind, hops, device_ts, lat_e7, lon_e7, accuracy_m, battery_pct)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (frame_id) DO NOTHING`,
      [s.frameId, s.alertId, s.deviceId, s.flags, s.kind, s.hops, s.deviceTs, s.latE7, s.lonE7, s.accuracyM, s.batteryPct],
    );
    return n > 0;
  }

  async insertReceipt(r: Receipt): Promise<void> {
    await this.db.run(
      "INSERT INTO receipts (frame_id, channel, relay_device_id, sms_from, hops, received_at) VALUES (?, ?, ?, ?, ?, ?)",
      [r.frameId, r.channel, r.relayDeviceId, r.smsFrom, r.hops, r.receivedAt],
    );
  }

  async signalsForAlert(alertId: string): Promise<Array<SignalRow & { receipts: Receipt[] }>> {
    const rows = await this.db.all("SELECT * FROM signals WHERE alert_id = ? ORDER BY device_ts", [alertId]);
    const out: Array<SignalRow & { receipts: Receipt[] }> = [];
    for (const r of rows) {
      const receipts = await this.db.all("SELECT * FROM receipts WHERE frame_id = ? ORDER BY received_at", [r.frame_id as string]);
      out.push({
        frameId: r.frame_id as string,
        alertId: r.alert_id as string,
        deviceId: r.device_id as string,
        flags: Number(r.flags),
        kind: Number(r.kind),
        hops: Number(r.hops),
        deviceTs: Number(r.device_ts),
        latE7: num(r.lat_e7),
        lonE7: num(r.lon_e7),
        accuracyM: num(r.accuracy_m),
        batteryPct: num(r.battery_pct),
        receipts: receipts.map((x) => ({
          frameId: x.frame_id as string,
          channel: x.channel as string,
          relayDeviceId: (x.relay_device_id as string | null) ?? null,
          smsFrom: (x.sms_from as string | null) ?? null,
          hops: Number(x.hops),
          receivedAt: Number(x.received_at),
        })),
      });
    }
    return out;
  }

  // --- alerts --------------------------------------------------------------

  async alertDeviceId(alertId: string): Promise<string | null> {
    const r = await this.db.get("SELECT device_id FROM alerts WHERE id = ?", [alertId]);
    return r ? (r.device_id as string) : null;
  }

  /** Returns false if the alert already existed. */
  async insertAlert(a: {
    id: string;
    deviceId: string;
    kind: string;
    status: AlertStatus;
    isTest: boolean;
    triggeredAt: number;
    now: number;
  }): Promise<boolean> {
    const n = await this.db.run(
      `INSERT INTO alerts (id, device_id, kind, status, is_test, triggered_at, first_received_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING`,
      [a.id, a.deviceId, a.kind, a.status, a.isTest ? 1 : 0, a.triggeredAt, a.now, a.now],
    );
    return n > 0;
  }

  /** Applies a newer location fix / battery reading; older fixes (late mesh deliveries) are ignored. */
  async applyTelemetry(
    alertId: string,
    t: { deviceTs: number; latE7: number | null; lonE7: number | null; accuracyM: number | null; batteryPct: number | null },
    now: number,
  ): Promise<void> {
    if (t.latE7 !== null && t.lonE7 !== null) {
      await this.db.run(
        `UPDATE alerts SET lat_e7 = ?, lon_e7 = ?, accuracy_m = ?, fix_at = ?
         WHERE id = ? AND (fix_at IS NULL OR fix_at <= ?)`,
        [t.latE7, t.lonE7, t.accuracyM, t.deviceTs, alertId, t.deviceTs],
      );
    }
    if (t.batteryPct !== null) {
      await this.db.run("UPDATE alerts SET battery_pct = ? WHERE id = ?", [t.batteryPct, alertId]);
    }
    await this.touch(alertId, now);
  }

  /** Earliest trigger time wins: frames can arrive out of order over different channels. */
  async noteTriggerTime(alertId: string, deviceTs: number): Promise<void> {
    await this.db.run("UPDATE alerts SET triggered_at = ? WHERE id = ? AND triggered_at > ?", [deviceTs, alertId, deviceTs]);
  }

  async touch(alertId: string, now: number): Promise<void> {
    await this.db.run("UPDATE alerts SET updated_at = ? WHERE id = ?", [now, alertId]);
  }

  async setStatus(
    alertId: string,
    status: AlertStatus,
    now: number,
    extra: { acknowledgedBy?: string; note?: string } = {},
  ): Promise<void> {
    const cols: string[] = ["status = ?", "updated_at = ?"];
    const vals: Param[] = [status, now];
    if (status === "acknowledged") {
      cols.push("acknowledged_at = COALESCE(acknowledged_at, ?)", "acknowledged_by = ?");
      vals.push(now, extra.acknowledgedBy ?? null);
    }
    if (status === "resolved") {
      cols.push("resolved_at = ?");
      vals.push(now);
    }
    if (extra.note !== undefined) {
      cols.push("note = ?");
      vals.push(extra.note);
    }
    vals.push(alertId);
    await this.db.run(`UPDATE alerts SET ${cols.join(", ")} WHERE id = ?`, vals);
  }

  async getAlert(id: string): Promise<Alert | null> {
    const r = await this.db.get(
      "SELECT a.*, d.name AS device_name, d.phone AS device_phone FROM alerts a JOIN devices d ON d.id = a.device_id WHERE a.id = ?",
      [id],
    );
    return r ? this.toAlert(r) : null;
  }

  async listAlerts(opts: { status?: AlertStatus[]; limit?: number } = {}): Promise<Alert[]> {
    const statuses = opts.status ?? ["open", "acknowledged"];
    const placeholders = statuses.map(() => "?").join(", ");
    const rows = await this.db.all(
      `SELECT a.*, d.name AS device_name, d.phone AS device_phone FROM alerts a JOIN devices d ON d.id = a.device_id
       WHERE a.status IN (${placeholders}) ORDER BY a.updated_at DESC LIMIT ?`,
      [...statuses, opts.limit ?? 100],
    );
    const out: Alert[] = [];
    for (const r of rows) out.push(await this.toAlert(r));
    return out;
  }

  private async toAlert(r: Row): Promise<Alert> {
    const channels = (
      await this.db.all(
        "SELECT DISTINCT r.channel FROM receipts r JOIN signals s ON s.frame_id = r.frame_id WHERE s.alert_id = ? ORDER BY r.channel",
        [r.id as string],
      )
    ).map((x) => x.channel as string);
    const hasFix = r.lat_e7 !== null && r.lon_e7 !== null;
    return {
      id: r.id as string,
      deviceId: r.device_id as string,
      deviceName: (r.device_name as string | null) ?? null,
      devicePhone: (r.device_phone as string | null) ?? null,
      kind: r.kind as string,
      status: r.status as AlertStatus,
      isTest: Number(r.is_test) === 1,
      triggeredAt: Number(r.triggered_at),
      firstReceivedAt: Number(r.first_received_at),
      updatedAt: Number(r.updated_at),
      location: hasFix
        ? { lat: Number(r.lat_e7) / 1e7, lon: Number(r.lon_e7) / 1e7, accuracyM: num(r.accuracy_m), fixAt: Number(r.fix_at) }
        : null,
      batteryPct: num(r.battery_pct),
      channels,
      acknowledgedBy: (r.acknowledged_by as string | null) ?? null,
      acknowledgedAt: num(r.acknowledged_at),
      resolvedAt: num(r.resolved_at),
      note: (r.note as string | null) ?? null,
    };
  }
}

function num(v: unknown): number | null {
  return v === null || v === undefined ? null : Number(v);
}
