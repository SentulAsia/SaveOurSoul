/**
 * SOS wire protocol v1 — a compact, signed, transport-agnostic panic frame.
 *
 * The exact same 83 bytes travel over every channel (HTTPS, SMS, Bluetooth /
 * Wi-Fi mesh relays), so the backend can verify a panic no matter which path
 * it took. See docs/PROTOCOL.md for the full spec.
 */
import { createHmac, timingSafeEqual } from "node:crypto";

export const PROTOCOL_VERSION = 1;
export const FRAME_LENGTH = 83;
export const SIGNED_LENGTH = 67;
export const MAC_LENGTH = 16;
export const SMS_PREFIX = "SOS1:";
export const MAX_HOPS = 8;

export const Flags = {
  HAS_LOCATION: 0x01,
  CANCEL: 0x02,
  TEST: 0x04,
} as const;

/** Order is part of the wire protocol. "lost" (separated from the group) is the most common Hajj case. */
export const Kinds = ["general", "medical", "fire", "violence", "accident", "lost"] as const;
export type Kind = (typeof Kinds)[number];

export const UNKNOWN_ACCURACY = 0xffff;
export const UNKNOWN_BATTERY = 0xff;

export interface PanicFrame {
  version: number;
  flags: number;
  kind: number;
  /** Relay hop counter. Not covered by the MAC; relays increment it. */
  hops: number;
  frameId: string; // uuid
  alertId: string; // uuid
  deviceId: string; // uuid
  /** Unix seconds when the device created the frame. */
  timestamp: number;
  latE7: number;
  lonE7: number;
  accuracyM: number;
  batteryPct: number;
}

export class FrameError extends Error {}

function uuidToBytes(uuid: string): Buffer {
  const hex = uuid.replace(/-/g, "");
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) throw new FrameError(`bad uuid: ${uuid}`);
  return Buffer.from(hex, "hex");
}

function bytesToUuid(b: Buffer): string {
  const h = b.toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function mac(signed: Buffer, secret: Buffer): Buffer {
  const copy = Buffer.from(signed);
  copy[3] = 0; // hops are mutable in transit, so they are excluded from the MAC
  return createHmac("sha256", secret).update(copy).digest().subarray(0, MAC_LENGTH);
}

export function encodeFrame(f: PanicFrame, secret: Buffer): Buffer {
  const b = Buffer.alloc(FRAME_LENGTH);
  b.writeUInt8(f.version, 0);
  b.writeUInt8(f.flags, 1);
  b.writeUInt8(f.kind, 2);
  b.writeUInt8(f.hops, 3);
  uuidToBytes(f.frameId).copy(b, 4);
  uuidToBytes(f.alertId).copy(b, 20);
  uuidToBytes(f.deviceId).copy(b, 36);
  b.writeUInt32BE(f.timestamp >>> 0, 52);
  b.writeInt32BE(f.latE7, 56);
  b.writeInt32BE(f.lonE7, 60);
  b.writeUInt16BE(f.accuracyM, 64);
  b.writeUInt8(f.batteryPct, 66);
  mac(b.subarray(0, SIGNED_LENGTH), secret).copy(b, SIGNED_LENGTH);
  return b;
}

/** Parses a frame without checking its MAC (the device secret is looked up by deviceId first). */
export function decodeFrame(b: Buffer): PanicFrame {
  if (b.length !== FRAME_LENGTH) throw new FrameError(`frame must be ${FRAME_LENGTH} bytes, got ${b.length}`);
  const version = b.readUInt8(0);
  if (version !== PROTOCOL_VERSION) throw new FrameError(`unsupported version ${version}`);
  return {
    version,
    flags: b.readUInt8(1),
    kind: b.readUInt8(2),
    hops: b.readUInt8(3),
    frameId: bytesToUuid(b.subarray(4, 20)),
    alertId: bytesToUuid(b.subarray(20, 36)),
    deviceId: bytesToUuid(b.subarray(36, 52)),
    timestamp: b.readUInt32BE(52),
    latE7: b.readInt32BE(56),
    lonE7: b.readInt32BE(60),
    accuracyM: b.readUInt16BE(64),
    batteryPct: b.readUInt8(66),
  };
}

export function verifyFrame(b: Buffer, secret: Buffer): boolean {
  if (b.length !== FRAME_LENGTH) return false;
  const expected = mac(b.subarray(0, SIGNED_LENGTH), secret);
  return timingSafeEqual(expected, b.subarray(SIGNED_LENGTH));
}

export function toBase64Url(b: Buffer): string {
  return b.toString("base64url");
}

export function fromBase64Url(s: string): Buffer {
  const trimmed = s.trim();
  if (!/^[A-Za-z0-9_-]+$/.test(trimmed)) throw new FrameError("frame is not base64url");
  return Buffer.from(trimmed, "base64url");
}

/** Extracts every frame from free text such as an SMS body ("SOS1:<base64url>" tokens). */
export function extractSmsFrames(text: string): string[] {
  const out: string[] = [];
  const re = /SOS1:([A-Za-z0-9_-]{100,120})/g;
  for (const m of text.matchAll(re)) out.push(m[1]!);
  return out;
}

export const RECEIPT_LENGTH = 8;
const RECEIPT_DOMAIN = Buffer.from("SOSACK1");

/**
 * Delivery receipt for a frame: HMAC(device secret, "SOSACK1" || frameId)[:8].
 * Only the backend and the sending phone hold the secret, so when relays carry a receipt back
 * over Bluetooth the victim's phone can tell a genuine "server has it" from a forged one that
 * would silence its SOS.
 */
export function receiptFor(frameId: string, secret: Buffer): Buffer {
  return createHmac("sha256", secret)
    .update(Buffer.concat([RECEIPT_DOMAIN, uuidToBytes(frameId)]))
    .digest()
    .subarray(0, RECEIPT_LENGTH);
}

export function kindName(kind: number): Kind {
  return Kinds[kind] ?? "general";
}
