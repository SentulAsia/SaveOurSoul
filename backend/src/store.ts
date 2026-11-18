import { DatabaseSync } from "node:sqlite";

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

const SCHEMA = `
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  secret BLOB NOT NULL,
  name TEXT NOT NULL,
  phone TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS alerts (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id),
  kind TEXT NOT NULL,
  status TEXT NOT NULL,
  is_test INTEGER NOT NULL DEFAULT 0,
  triggered_at INTEGER NOT NULL,
  first_received_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  lat_e7 INTEGER, lon_e7 INTEGER, accuracy_m INTEGER, fix_at INTEGER,
  battery_pct INTEGER,
  acknowledged_by TEXT, acknowledged_at INTEGER, resolved_at INTEGER, note TEXT
);
CREATE INDEX IF NOT EXISTS alerts_status ON alerts(status, updated_at);
CREATE TABLE IF NOT EXISTS signals (
  frame_id TEXT PRIMARY KEY,
  alert_id TEXT NOT NULL REFERENCES alerts(id),
  device_id TEXT NOT NULL,
  flags INTEGER NOT NULL,
  kind INTEGER NOT NULL,
  hops INTEGER NOT NULL,
  device_ts INTEGER NOT NULL,
  lat_e7 INTEGER, lon_e7 INTEGER, accuracy_m INTEGER, battery_pct INTEGER
);
CREATE INDEX IF NOT EXISTS signals_alert ON signals(alert_id, device_ts);
CREATE TABLE IF NOT EXISTS receipts (
  frame_id TEXT NOT NULL REFERENCES signals(frame_id),
  channel TEXT NOT NULL,
  relay_device_id TEXT,
  sms_from TEXT,
  hops INTEGER NOT NULL,
  received_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS receipts_frame ON receipts(frame_id);
`;

type Row = Record<string, unknown>;

export class Store {
  readonly db: DatabaseSync;

  constructor(path = ":memory:") {
    this.db = new DatabaseSync(path);
    this.db.exec(SCHEMA);
  }

  close(): void {
    this.db.close();
  }

  transaction<T>(fn: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const out = fn();
      this.db.exec("COMMIT");
      return out;
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
  }

  // --- devices -------------------------------------------------------------

  insertDevice(d: Device): void {
    this.db
      .prepare("INSERT INTO devices (id, secret, name, phone, created_at) VALUES (?, ?, ?, ?, ?)")
      .run(d.id, d.secret, d.name, d.phone, d.createdAt);
  }

  getDevice(id: string): Device | null {
    const r = this.db.prepare("SELECT * FROM devices WHERE id = ?").get(id) as Row | undefined;
    if (!r) return null;
    return {
      id: r.id as string,
      secret: Buffer.from(r.secret as Uint8Array),
      name: r.name as string,
      phone: (r.phone as string | null) ?? null,
      createdAt: r.created_at as number,
    };
  }

  // --- signals ---------------------------------------------------------------

  hasSignal(frameId: string): boolean {
    return this.db.prepare("SELECT 1 FROM signals WHERE frame_id = ?").get(frameId) !== undefined;
  }

  insertSignal(s: SignalRow): void {
    this.db
      .prepare(
        `INSERT INTO signals (frame_id, alert_id, device_id, flags, kind, hops, device_ts, lat_e7, lon_e7, accuracy_m, battery_pct)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(s.frameId, s.alertId, s.deviceId, s.flags, s.kind, s.hops, s.deviceTs, s.latE7, s.lonE7, s.accuracyM, s.batteryPct);
  }

  insertReceipt(r: Receipt): void {
    this.db
      .prepare("INSERT INTO receipts (frame_id, channel, relay_device_id, sms_from, hops, received_at) VALUES (?, ?, ?, ?, ?, ?)")
      .run(r.frameId, r.channel, r.relayDeviceId, r.smsFrom, r.hops, r.receivedAt);
  }

  signalsForAlert(alertId: string): Array<SignalRow & { receipts: Receipt[] }> {
    const rows = this.db.prepare("SELECT * FROM signals WHERE alert_id = ? ORDER BY device_ts").all(alertId) as Row[];
    const rq = this.db.prepare("SELECT * FROM receipts WHERE frame_id = ? ORDER BY received_at");
    return rows.map((r) => ({
      frameId: r.frame_id as string,
      alertId: r.alert_id as string,
      deviceId: r.device_id as string,
      flags: r.flags as number,
      kind: r.kind as number,
      hops: r.hops as number,
      deviceTs: r.device_ts as number,
      latE7: r.lat_e7 as number | null,
      lonE7: r.lon_e7 as number | null,
      accuracyM: r.accuracy_m as number | null,
      batteryPct: r.battery_pct as number | null,
      receipts: (rq.all(r.frame_id as string) as Row[]).map((x) => ({
        frameId: x.frame_id as string,
        channel: x.channel as string,
        relayDeviceId: x.relay_device_id as string | null,
        smsFrom: x.sms_from as string | null,
        hops: x.hops as number,
        receivedAt: x.received_at as number,
      })),
    }));
  }

  // --- alerts --------------------------------------------------------------

  alertDeviceId(alertId: string): string | null {
    const r = this.db.prepare("SELECT device_id FROM alerts WHERE id = ?").get(alertId) as Row | undefined;
    return r ? (r.device_id as string) : null;
  }

  insertAlert(a: {
    id: string;
    deviceId: string;
    kind: string;
    status: AlertStatus;
    isTest: boolean;
    triggeredAt: number;
    now: number;
  }): void {
    this.db
      .prepare(
        `INSERT INTO alerts (id, device_id, kind, status, is_test, triggered_at, first_received_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(a.id, a.deviceId, a.kind, a.status, a.isTest ? 1 : 0, a.triggeredAt, a.now, a.now);
  }

  /** Applies a newer location fix / battery reading; older fixes (late mesh deliveries) are ignored. */
  applyTelemetry(
    alertId: string,
    t: { deviceTs: number; latE7: number | null; lonE7: number | null; accuracyM: number | null; batteryPct: number | null },
    now: number,
  ): void {
    if (t.latE7 !== null && t.lonE7 !== null) {
      this.db
        .prepare(
          `UPDATE alerts SET lat_e7 = ?, lon_e7 = ?, accuracy_m = ?, fix_at = ?
           WHERE id = ? AND (fix_at IS NULL OR fix_at <= ?)`,
        )
        .run(t.latE7, t.lonE7, t.accuracyM, t.deviceTs, alertId, t.deviceTs);
    }
    if (t.batteryPct !== null) {
      this.db.prepare("UPDATE alerts SET battery_pct = ? WHERE id = ?").run(t.batteryPct, alertId);
    }
    this.touch(alertId, now);
  }

  /** Earliest trigger time wins: frames can arrive out of order over different channels. */
  noteTriggerTime(alertId: string, deviceTs: number): void {
    this.db.prepare("UPDATE alerts SET triggered_at = MIN(triggered_at, ?) WHERE id = ?").run(deviceTs, alertId);
  }

  touch(alertId: string, now: number): void {
    this.db.prepare("UPDATE alerts SET updated_at = ? WHERE id = ?").run(now, alertId);
  }

  setStatus(
    alertId: string,
    status: AlertStatus,
    now: number,
    extra: { acknowledgedBy?: string; note?: string } = {},
  ): void {
    const cols: string[] = ["status = ?", "updated_at = ?"];
    const vals: Array<string | number | null> = [status, now];
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
    this.db.prepare(`UPDATE alerts SET ${cols.join(", ")} WHERE id = ?`).run(...vals);
  }

  getAlert(id: string): Alert | null {
    const r = this.db
      .prepare("SELECT a.*, d.name AS device_name, d.phone AS device_phone FROM alerts a JOIN devices d ON d.id = a.device_id WHERE a.id = ?")
      .get(id) as Row | undefined;
    return r ? this.toAlert(r) : null;
  }

  listAlerts(opts: { status?: AlertStatus[]; limit?: number } = {}): Alert[] {
    const statuses = opts.status ?? ["open", "acknowledged"];
    const placeholders = statuses.map(() => "?").join(", ");
    const rows = this.db
      .prepare(
        `SELECT a.*, d.name AS device_name, d.phone AS device_phone FROM alerts a JOIN devices d ON d.id = a.device_id
         WHERE a.status IN (${placeholders}) ORDER BY a.updated_at DESC LIMIT ?`,
      )
      .all(...statuses, opts.limit ?? 100) as Row[];
    return rows.map((r) => this.toAlert(r));
  }

  private toAlert(r: Row): Alert {
    const channels = (
      this.db
        .prepare(
          "SELECT DISTINCT r.channel FROM receipts r JOIN signals s ON s.frame_id = r.frame_id WHERE s.alert_id = ? ORDER BY r.channel",
        )
        .all(r.id as string) as Row[]
    ).map((x) => x.channel as string);
    const hasFix = r.lat_e7 !== null && r.lon_e7 !== null;
    return {
      id: r.id as string,
      deviceId: r.device_id as string,
      deviceName: (r.device_name as string | null) ?? null,
      devicePhone: (r.device_phone as string | null) ?? null,
      kind: r.kind as string,
      status: r.status as AlertStatus,
      isTest: r.is_test === 1,
      triggeredAt: r.triggered_at as number,
      firstReceivedAt: r.first_received_at as number,
      updatedAt: r.updated_at as number,
      location: hasFix
        ? {
            lat: (r.lat_e7 as number) / 1e7,
            lon: (r.lon_e7 as number) / 1e7,
            accuracyM: r.accuracy_m as number | null,
            fixAt: r.fix_at as number,
          }
        : null,
      batteryPct: r.battery_pct as number | null,
      channels,
      acknowledgedBy: r.acknowledged_by as string | null,
      acknowledgedAt: r.acknowledged_at as number | null,
      resolvedAt: r.resolved_at as number | null,
      note: r.note as string | null,
    };
  }
}
