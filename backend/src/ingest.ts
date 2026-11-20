import { EventEmitter } from "node:events";
import {
  Flags,
  FrameError,
  MAX_HOPS,
  UNKNOWN_ACCURACY,
  UNKNOWN_BATTERY,
  decodeFrame,
  fromBase64Url,
  kindName,
  receiptFor,
  verifyFrame,
} from "./protocol.ts";
import type { Alert, AlertStatus, Store } from "./store.ts";

/** Channels a frame can arrive on. Relays report how *they* received it from the victim's phone. */
export const Channels = ["internet", "sms", "bluetooth", "wifi-direct", "mesh"] as const;
export type Channel = (typeof Channels)[number];

export interface IngestContext {
  channel: Channel;
  relayDeviceId?: string | null;
  smsFrom?: string | null;
  /** Unix seconds. */
  now: number;
}

export type IngestResult =
  | {
      status: "accepted" | "duplicate";
      frameId: string;
      alertId: string;
      alertStatus: AlertStatus;
      /** base64url delivery receipt the sending phone can verify (see receiptFor). */
      receipt: string;
    }
  | { status: "rejected"; frameId?: string; reason: string };

export interface IngestOptions {
  /** Frames older than this (seconds) are rejected. Mesh store-and-forward can be slow, so keep it generous. */
  maxAgeSec: number;
  /** Allowed device clock skew into the future (seconds). */
  maxFutureSkewSec: number;
}

export const DEFAULT_INGEST_OPTIONS: IngestOptions = { maxAgeSec: 72 * 3600, maxFutureSkewSec: 300 };

export type AlertEvent = { type: "alert.created" | "alert.updated"; alert: Alert };

export class Ingestor {
  readonly events = new EventEmitter();
  private readonly store: Store;
  private readonly opts: IngestOptions;

  constructor(store: Store, opts: Partial<IngestOptions> = {}) {
    this.store = store;
    this.opts = { ...DEFAULT_INGEST_OPTIONS, ...opts };
    this.events.setMaxListeners(1000);
  }

  async ingest(frameB64: string, ctx: IngestContext): Promise<IngestResult> {
    let raw: Buffer;
    let frame;
    try {
      raw = fromBase64Url(frameB64);
      frame = decodeFrame(raw);
    } catch (e) {
      return { status: "rejected", reason: e instanceof FrameError ? e.message : "malformed_frame" };
    }
    const { frameId } = frame;

    const device = await this.store.getDevice(frame.deviceId);
    if (!device) return { status: "rejected", frameId, reason: "unknown_device" };
    if (!verifyFrame(raw, device.secret)) return { status: "rejected", frameId, reason: "bad_signature" };
    if (frame.hops > MAX_HOPS) return { status: "rejected", frameId, reason: "too_many_hops" };
    if (frame.timestamp > ctx.now + this.opts.maxFutureSkewSec) return { status: "rejected", frameId, reason: "timestamp_in_future" };
    if (frame.timestamp < ctx.now - this.opts.maxAgeSec) return { status: "rejected", frameId, reason: "stale" };

    const receipt = {
      frameId,
      channel: ctx.channel,
      relayDeviceId: ctx.relayDeviceId ?? null,
      smsFrom: ctx.smsFrom ?? null,
      hops: frame.hops,
      receivedAt: ctx.now,
    };
    const receipt64 = receiptFor(frameId, device.secret).toString("base64url");
    const isCancel = (frame.flags & Flags.CANCEL) !== 0;
    const hasLoc = (frame.flags & Flags.HAS_LOCATION) !== 0;
    const signal = {
      frameId,
      alertId: frame.alertId,
      deviceId: frame.deviceId,
      flags: frame.flags,
      kind: frame.kind,
      hops: frame.hops,
      deviceTs: frame.timestamp,
      latE7: hasLoc ? frame.latE7 : null,
      lonE7: hasLoc ? frame.lonE7 : null,
      accuracyM: hasLoc && frame.accuracyM !== UNKNOWN_ACCURACY ? frame.accuracyM : null,
      batteryPct: frame.batteryPct !== UNKNOWN_BATTERY ? frame.batteryPct : null,
    };

    let created = false;
    // In a crowd, many relays upload the same frame at the same moment: the inserts below use
    // ON CONFLICT DO NOTHING, so exactly one upload wins and the others become duplicates.
    const outcome = await this.store.transaction(async (s): Promise<IngestResult> => {
      const owner = await s.alertDeviceId(frame.alertId);
      if (owner !== null && owner !== frame.deviceId) {
        return { status: "rejected", frameId, reason: "alert_device_mismatch" };
      }
      if (owner === null) {
        created = await s.insertAlert({
          id: frame.alertId,
          deviceId: frame.deviceId,
          kind: kindName(frame.kind),
          status: isCancel ? "cancelled" : "open",
          isTest: (frame.flags & Flags.TEST) !== 0,
          triggeredAt: frame.timestamp,
          now: ctx.now,
        });
      }

      if (!(await s.insertSignal(signal))) {
        // Same frame via another channel/relay: record the path for diagnostics, nothing else changes.
        await s.insertReceipt(receipt);
        return { status: "duplicate", frameId, alertId: frame.alertId, alertStatus: (await s.getAlert(frame.alertId))!.status, receipt: receipt64 };
      }

      await s.insertReceipt(receipt);
      await s.noteTriggerTime(frame.alertId, frame.timestamp);
      await s.applyTelemetry(frame.alertId, signal, ctx.now);

      const current = (await s.getAlert(frame.alertId))!;
      if (isCancel && (current.status === "open" || current.status === "acknowledged")) {
        await s.setStatus(frame.alertId, "cancelled", ctx.now, { note: "cancelled by user" });
      }
      return { status: "accepted", frameId, alertId: frame.alertId, alertStatus: (await s.getAlert(frame.alertId))!.status, receipt: receipt64 };
    });

    if (outcome.status === "accepted") {
      const alert = (await this.store.getAlert(outcome.alertId))!;
      this.events.emit("alert", { type: created ? "alert.created" : "alert.updated", alert } satisfies AlertEvent);
    }
    return outcome;
  }

  /** Responder actions. Returns null if the alert does not exist. */
  async transition(alertId: string, to: "acknowledged" | "resolved", now: number, by?: string, note?: string): Promise<Alert | null> {
    const alert = await this.store.getAlert(alertId);
    if (!alert) return null;
    if (alert.status === "resolved" || alert.status === "cancelled") return alert;
    const extra: { acknowledgedBy?: string; note?: string } = {};
    if (by !== undefined) extra.acknowledgedBy = by;
    if (note !== undefined) extra.note = note;
    await this.store.setStatus(alertId, to, now, extra);
    const updated = (await this.store.getAlert(alertId))!;
    this.events.emit("alert", { type: "alert.updated", alert: updated } satisfies AlertEvent);
    return updated;
  }
}
