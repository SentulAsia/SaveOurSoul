# Environments: dev, test, prod

| | dev | test | prod |
|---|---|---|---|
| Purpose | day-to-day work on your computer | field trials and QA before release | real pilgrims |
| Runs on | your laptop | Render `saveoursoul-api-test` | Render `saveoursoul-api` |
| Database | **SQLite** file (`backend/sos.db`) | **PostgreSQL**, Neon branch `test` | **PostgreSQL**, Neon branch `production` |
| `APP_ENV` | `dev` (the default) | `test` | `prod` |
| Git branch that deploys | — | `test` | `main` |
| Android variant | `devDebug` ("SOS Dev") | `qaDebug` / `qaRelease` ("SOS Test") ¹ | `prodDebug` / `prodRelease` ("SaveOurSoul") |
| iOS scheme | `SaveOurSoul Dev` | `SaveOurSoul Test` | `SaveOurSoul` |
| App's default server | your computer, `:8080` | test service URL | prod service URL |
| Bluetooth/Nearby namespace | DEV | TEST | PROD |

¹ Android doesn't allow build flavors whose names start with "test", so the test environment's
flavor is called `qa`. It still reports `APP_ENV=test`.

**dev always uses SQLite.** Even if `DATABASE_URL` is set, `APP_ENV=dev` ignores it (with a warning),
so a laptop can never write to the test or production database by accident.

**test and prod always use PostgreSQL.** They refuse to start without a PostgreSQL `DATABASE_URL`,
because a SQLite file on Render would be wiped on every deploy.

**Separate radio namespaces.** Phones running a dev or test build use different Bluetooth service
UUIDs and Nearby service ids. They never pick up a real pilgrim's SOS, and test SOS signals never
reach production phones.

Code moves one way: feature branch → `test` → `main`, through pull requests. Merging into `test`
or `main` deploys automatically.

## dev: run everything on your computer

```bash
cd backend
npm install
RESPONDER_API_KEY=dev npm start          # APP_ENV defaults to dev → SQLite file backend/sos.db
```

Delete `backend/sos.db` to start from an empty database.

* **Android emulator:** `./gradlew installDevDebug`. The app already points to
  `http://10.0.2.2:8080`, which is your computer as seen from the emulator.
* **iOS simulator:** run the `SaveOurSoul Dev` scheme. It points to `http://localhost:8080`.
* **Real phones** (needed for Bluetooth): put the phone on the same Wi-Fi network as your computer,
  then in the app's registration screen set the server URL to `http://<your computer's LAN IP>:8080`.
  Dev builds allow plain HTTP; test and prod builds are HTTPS only.

## One-time setup for test and prod

### 1. Neon: a database branch for test

The project `SaveOurSoul` (Singapore) already has the `production` branch, which prod uses.

1. Neon console → **Branches** → **Create branch** → name `test`, parent `production`.
2. For each of `test` and `production`: **Connect** → select the branch → turn on
   **Connection pooling** → **Copy snippet**. Never put these strings in git or chat.

The backend creates its tables on first start.

### 2. Git: the branch that feeds test

```bash
git checkout main && git pull
git checkout -b test && git push -u origin test
```

On GitHub, consider protecting `main` and `test` (Settings → Branches) so they only change through
reviewed pull requests with green CI.

### 3. Render: deploy both from the Blueprint

1. Render dashboard → **New** → **Blueprint** → choose this repository. Render reads `render.yaml`.
2. Paste each `DATABASE_URL`: the Neon `test` string → `saveoursoul-api-test`, the `production`
   string → `saveoursoul-api`.
3. Render generates `RESPONDER_API_KEY`, `ENROLL_TOKEN` and `SMS_WEBHOOK_TOKEN`. Read them in each
   service's **Environment** tab when you need them.
4. Open `<service URL>/healthz` for each. It should return `{"ok":true,"env":"test","db":"postgres"}`
   (or `"prod"`).

If Render assigns different URLs than `https://saveoursoul-api-test.onrender.com` and
`https://saveoursoul-api.onrender.com`, update them in `android/app/build.gradle.kts`
(productFlavors) and `ios/project.yml` (`SOS_BACKEND_URL`).

> **Before go-live:** change prod's plan from `free` to `starter` or higher. Free instances
> sleep after ~15 minutes idle and take up to a minute to wake, and an SOS cannot wait that
> long. Also consider a paid Neon plan for production (point-in-time recovery, no scale-to-zero delay).

### 4. SMS gateway

Point the SMS number for each environment at
`https://<service URL>/api/v1/sms/inbound?token=<that service's SMS_WEBHOOK_TOKEN>`.

## Tests and schema changes

* `npm test` runs on SQLite. `TEST_DATABASE_URL=postgres://… npm run test:pg` runs the same API
  suite on PostgreSQL. **Never point it at test or prod: it drops the tables.** CI runs both,
  against a throwaway Postgres.
* To change the schema, append a new entry to `migrations()` in `backend/src/store.ts` (never edit
  a released one). It runs locally on SQLite first, then on test and prod when the code is
  deployed there.
