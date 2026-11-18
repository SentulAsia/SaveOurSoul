# SOS wire protocol v1

One binary **panic frame** is used on every channel. Because it is signed by the phone, the
backend can trust a frame whether it came over HTTPS, as an SMS through a gateway, or after
hopping through strangers' phones over Bluetooth / Wi-Fi.

## Frame layout (83 bytes, big-endian)

| Offset | Size | Field        | Notes |
|-------:|-----:|--------------|-------|
| 0      | 1    | `version`    | `1` |
| 1      | 1    | `flags`      | bit0 `HAS_LOCATION`, bit1 `CANCEL`, bit2 `TEST` |
| 2      | 1    | `kind`       | 0 general, 1 medical, 2 fire, 3 violence, 4 accident |
| 3      | 1    | `hops`       | Relay hop counter. **Not signed**: each relay increments it. Max 8. |
| 4      | 16   | `frameId`    | UUID, unique per frame. Used for de-duplication. |
| 20     | 16   | `alertId`    | UUID, one per incident. Location updates and the cancel share it. |
| 36     | 16   | `deviceId`   | UUID issued at enrollment. |
| 52     | 4    | `timestamp`  | Unix seconds (uint32) when the phone created the frame. |
| 56     | 4    | `latE7`      | Latitude × 10⁷ (int32). |
| 60     | 4    | `lonE7`      | Longitude × 10⁷ (int32). |
| 64     | 2    | `accuracyM`  | Metres, `0xFFFF` = unknown. |
| 66     | 1    | `batteryPct` | 0–100, `0xFF` = unknown. |
| 67     | 16   | `mac`        | First 16 bytes of HMAC-SHA256(device secret, bytes 0..66 with `hops` set to 0). |

## Encodings per channel

| Channel | Encoding |
|---|---|
| HTTPS | `POST /api/v1/signals` with `{"frames": ["<base64url>", ...], "channel": "internet"}` |
| SMS | Text `SOS1:<base64url>`: 116 GSM-7 characters, always a single SMS. The gateway (e.g. Twilio) posts it to `POST /api/v1/sms/inbound`. |
| Bluetooth / Wi-Fi mesh | Raw bytes. One Nearby Connections payload holds several frames back to back (`n × 83` bytes). |
| Relay upload | A relay that reaches the internet posts the frames with `"channel": "mesh"` and its own `relayDeviceId`. |

## Rules

* **Integrity:** the backend looks up the device secret by `deviceId` and checks the MAC. Relays
  cannot check it because they do not hold the key, so they forward any well-formed frame.
* **De-duplication:** the backend uses `frameId`. When the same frame arrives on several channels
  it is stored once, and every path it took is recorded as a *receipt*.
* **Replay window:** frames older than 72 h, or more than 5 min in the future, are rejected.
  Replaying a frame inside the window is harmless because of de-duplication.
* **Flooding stops:** relays drop any `frameId` they have already seen, and any frame whose
  hop counter would go past 8.
* **Cancel:** a frame with the `CANCEL` flag and the same `alertId` moves the alert to
  `cancelled`. If the cancel arrives first (frames can arrive out of order), the alert is
  created already cancelled.
* **Ordering:** the newest `timestamp` decides the alert's location, and the oldest decides
  `triggeredAt`.

## Privacy note

Frames are signed but **not encrypted**. A phone that relays a frame can read its location,
which in an emergency is often acceptable (bystanders can help). If it is not acceptable for
you, encrypt bytes 1–66 to a backend public key (for example X25519 + AES-GCM). That makes
the frame longer, and the SMS encoding would need re-checking against the 160-character limit.

`protocol/test-vectors.json` holds shared vectors. The TypeScript and Kotlin test suites both
check them, so the two implementations stay byte-for-byte compatible
(`cd backend && npm run vectors` regenerates them).
