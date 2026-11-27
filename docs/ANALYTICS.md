# Analytics (Firebase)

Anonymous, **opt-in** usage analytics in the Android and iOS apps, with one Firebase project per
environment. It answers questions like *which channel actually delivers SOS signals at Arafat,
and how fast?* It does not track people.

## Rules (both apps)

1. **Opt-in.** Collection is disabled by default (Android manifest / iOS Info.plist). A card
   below the SOS button asks once, and a toggle in Settings changes it later. "No" also wipes any
   locally stored analytics data.
2. **Never in the way of an SOS.** Every call is fire-and-forget and swallows errors. Without a
   Firebase config file the app runs normally, just without analytics.
3. **No personal data.** Never location, device id, alert or frame ids, names, phone numbers,
   contacts or message text. Parameters are categories and coarse time buckets only.
4. **No advertising.** Ad storage, ad user data and ad personalisation are always denied.
   Advertising-ID collection is off and the Android `AD_ID` permission is removed.

Code: `android/app/.../Analytics.kt` and `ios/SaveOurSoul/AppAnalytics.swift`. Keep event names and
parameters identical on both platforms.

## Events

| Event | When | Parameters |
|---|---|---|
| `sos_triggered` | SOS started | `kind` (general, medical, fire, violence, accident, lost), `registered` (yes/no) |
| `sos_delivered` | first confirmation that the server has the SOS | `channel` (internet, bluetooth), `network` (Android: wifi, cellular, …), `kind`, `time_bucket` |
| `sos_sms_sent` | an SMS left the phone | `to` (gateway, contacts), `cancel` (Android) |
| `sos_acknowledged` | a responder acknowledged | `kind`, `time_bucket` |
| `sos_resolved` | a responder resolved it | `kind`, `time_bucket` |
| `sos_cancelled` | the user confirmed "I'm safe" | `time_bucket` |
| `relay_carried` | this phone picked up others' frames | `frames` (count), `via` (ble, nearby) |
| `device_registered` | the phone registered with the server | — |

`time_bucket` is the time since the SOS started: `<10s`, `10-30s`, `30s-2m`, `2-10m`, `10-60m`,
`>1h`. Precise timings are never sent.

Events recorded offline (e.g. in a dead zone) are queued by the Firebase SDK and uploaded later.

## Setup: one Firebase project per environment

Create three projects in the [Firebase console](https://console.firebase.google.com):
`SaveOurSoul-dev`, `SaveOurSoul-qa`, `SaveOurSoul-prod`. In each, enable Google Analytics. When it
asks, choose to *not* share data with Google for advertising.

Register the apps with these identifiers:

| Project | Android package | iOS bundle id |
|---|---|---|
| SaveOurSoul-dev | `org.saveoursoul.app.dev` | `org.saveoursoul.ios.dev` |
| SaveOurSoul-qa | `org.saveoursoul.app.qa` | `org.saveoursoul.ios.qa` |
| SaveOurSoul-prod | `org.saveoursoul.app` | `org.saveoursoul.ios` |

Download the config files and place them (they are git-ignored, so not committed):

```
android/app/src/dev/google-services.json
android/app/src/qa/google-services.json
android/app/src/prod/google-services.json

ios/Firebase/Dev/GoogleService-Info.plist
ios/Firebase/QA/GoogleService-Info.plist
ios/Firebase/Prod/GoogleService-Info.plist
```

The Android google-services plugin picks the file for the flavor being built. On iOS, a build
phase copies the plist for the selected scheme. Run `xcodegen` again after pulling these changes.

For release builds in CI later, store the files as encrypted CI secrets and write them to these
paths before building.

### Checking it works

* **Android:** `adb shell setprop debug.firebase.analytics.app org.saveoursoul.app.dev`, run the
  dev build, accept the prompt, then trigger a test SOS and watch **Analytics → DebugView**.
* **iOS:** add `-FIRDebugEnabled` to the scheme's launch arguments, then use DebugView the same way.

## Store disclosures

Before publishing, update:

* **Google Play → Data safety:** "App interactions" and "Diagnostics", collected only with
  consent, not shared, not used for advertising.
* **App Store → App Privacy:** "Usage Data" (product interaction), not linked to identity, not
  used for tracking.

Mention the analytics in the privacy policy. Under Saudi PDPL and similar laws, the opt-in prompt
and the Settings toggle are the user's consent and withdrawal mechanism.
