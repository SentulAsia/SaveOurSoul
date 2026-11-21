# SOS-BLE v1: Bluetooth relay for very dense crowds

This document describes the transport that gets an SOS out when cellular networks are
saturated or absent: Arafat, Mina and Muzdalifah during Hajj, with millions of phones in a few
square kilometres. Android and iOS implement it identically:

- `android/core/.../BleProtocol.kt`, `android/app/.../BleTransport.kt`
- `ios/SOSCore/.../BleProtocol.swift`, `ios/SaveOurSoul/BleTransport.swift`

On Android, Nearby Connections is still used as well, mainly for higher bandwidth between
Android phones. iOS uses BLE only. BLE is the baseline: it needs no pairing, works between Android and iOS, and keeps
working in the background on both platforms.

## Why not just flood the mesh?

With hundreds of thousands of phones in Bluetooth range of each other, naive flooding (every
phone re-broadcasts every frame to every neighbour) saturates the three BLE advertising
channels within minutes. SOS-BLE instead:

1. **Prefers the shortest path to the internet.** Phones with *validated* internet advertise
   it. A phone holding an undelivered SOS connects to such a phone and pushes its frames, and
   the frames are uploaded within seconds. In practice someone within ~30 m almost always has
   *some* connectivity, even in Mina.
2. **Lets online phones collect.** A phone with internet pulls from any phone that advertises
   it holds frames, uploads them, and hands back receipts.
3. **Carries victims out of dead zones.** An offline phone always pulls a victim's frames, so
   the SOS physically walks out of the dead zone with the crowd. It pulls from plain relays only
   15 % of the time (probabilistic flooding).
4. **Stops as soon as the server has it.** Receipts flow back through the crowd. Relays stop
   advertising frames once a receipt is seen, and the inventory digest changes, so scanners skip
   phones they have already synced.
5. **Rate-limits itself.** Each phone contacts a given (peer, inventory) at most once a minute,
   ignores weak signals (< −92 dBm), and runs at most 3 GATT sessions at once.

## Advertisement

| Field | Android | iOS |
|---|---|---|
| Service UUID `5a0e5011-0000-4c8a-9f3e-5a0e50115a0e` | main advert packet | main advert |
| Beacon (version, flags, 24-bit digest) | service data in the scan response: `01 FF DD DD DD` | local name `S1` + flags hex + 6 hex digits, e.g. `S15abcdef` |

Flags: `0x01` own active SOS · `0x02` has validated internet · `0x04` holds undelivered frames.
The digest is the first 24 bits of SHA-256 over the sorted ids of the frames held.

## GATT service

| Characteristic | UUID suffix | Op | Value |
|---|---|---|---|
| INBOX | `-0001-` | write | n × 83-byte frames (≤ 6) |
| OUTBOX | `-0002-` | read | ≤ 6 frames, own first, rotated so successive readers get different ones |
| ACKS | `-0003-` | write | n × (frameId 16 + receipt 8) (≤ 21) |
| CONFIRMED | `-0004-` | read | receipts this phone knows about (≤ 21) |

All values are ≤ 512 bytes. Both stacks do long reads and writes on their own.

A session (central side): connect → MTU 247 → discover → [push: write INBOX] →
[pull: read OUTBOX → upload → write ACKS] → [push: wait 4 s] → read CONFIRMED → disconnect.
A session typically takes 1–3 s and is capped at 20–25 s.

## Receipts: a fake "delivered" cannot silence an SOS

The backend returns `receipt = HMAC(device secret, "SOSACK1" || frameId)[:8]` for every frame
it has. Only the backend and the sending phone hold that secret. A victim's phone marks its
own frame delivered only when a receipt **verifies with its own key**. A malicious phone could
at most make *relays* stop carrying a frame early; the victim keeps broadcasting.

## Platform behaviour

| | Android | iPhone, app open | iPhone, app in background |
|---|---|---|---|
| Others can see its advert | ✅ | ✅ | ⚠️ Other iPhones only. The beacon is stripped. |
| Scans and connects to others | ✅ | ✅ | ✅ with slower discovery (no duplicate reports) |
| Serves GATT (others pull from it) | ✅ | ✅ | ✅ once connected |

So with **the victim on Android**, every phone around can collect the SOS, including iPhones in
pockets. With **the victim on iPhone**, keep the SOS screen open. The app keeps the screen awake
during an SOS so Android phones can see it. With the screen locked, the iPhone still *pushes*
its SOS to any phone it sees advertising internet.

## Example: Android victim offline, iPhone B offline, iPhone C online

1. A (Android) advertises `own SOS | holds frames`.
2. C (online, even in the background) sees A and pulls. C uploads the frame, gets a receipt,
   and writes it to A's ACKS. A verifies the receipt, stops advertising "holds frames", and keeps
   advertising "own SOS".
3. If C is out of range, B (offline) sees A, pulls (victims are always carried), and later
   pushes the frame to the first online phone it sees. The receipt travels back the same way.

## Tuning knobs (to be set from field tests)

| Parameter | Default | Where |
|---|---|---|
| Re-contact cooldown | 60 s | `ContactPolicy` |
| Relay pull probability without internet | 0.15 | `ContactPolicy` |
| Minimum RSSI | −92 dBm | `ContactPolicy` |
| Concurrent GATT sessions | 3 | `BleTransport` |
| Advert mode | low-latency + high TX during own SOS, balanced otherwise | `BleTransport` (Android) |
| Max hops | 8 | `FrameCodec` |

## Field validation still required

Nothing here has been measured in a real crowd. Before Hajj:

* **Two-phone and ten-phone bench tests** across Android 8–15 and iOS 16–18, both directions,
  apps in the foreground and background, screen locked.
* **A density test** with 50–200 phones in a hall, measuring time from SOS to server, radio
  airtime and battery drain over 8 hours.
* **A pilot** in a crowded but controlled setting (e.g. Umrah season around the Haram, or a
  stadium) before relying on it at Arafat.
