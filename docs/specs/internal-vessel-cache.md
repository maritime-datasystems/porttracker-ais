# Spec: Internal Vessel Static Cache (persistent SQLite)

Status: draft · Owner: TBD · Target: porttracker-ais

## 1. Goal

Persist **static vessel data** (name, IMO, callsign, ship type, dimensions,
voyage info) keyed by transponder, so that:

- vessels can be identified immediately when a position arrives — including
  right after an app restart, before the next static message (Type 5 is only
  re-sent every ~6 minutes), and
- downstream consumers (MQTT publish, remote dashboard) and the map can show
  name/type without waiting for a fresh static frame, and
- there is a durable local registry of everything this station has ever seen.

Today all vessel data is RAM-only in the native engine and lost on restart
(see `docs`/architecture notes). The "Internal DB" UI toggle
(`internal_db_enabled`) is a dead no-op — collected into prefs, read nowhere.

### Non-goals
- Not the system of record. The authoritative cross-station registry belongs at
  TrustedDocks; this is a **local cache + station-local history**.
- Not a position/track logger. We store *static* identity, plus a cheap
  "last seen" timestamp/position — not full track history.

## 2. Key decision: MMSI as primary key (NOT composite IMO+MMSI)

Rationale (AIS specifics):
- **Position messages (types 1/2/3/18/19/27) carry only MMSI**, never IMO. The
  hot-path lookup can only be by MMSI.
- **IMO appears only in Type 5** (Class A). Class B (Type 24) has no IMO; many
  vessels send IMO=0. A composite `(imo, mmsi)` PK would insert `(0, mmsi)` on
  first contact, then a *second* row `(realimo, mmsi)` when the Type 5 arrives —
  duplicates, defeating "one persistent record per vessel".
- MMSI and IMO have different lifecycles (MMSI is reassignable/reusable; IMO is
  the permanent hull id). Welding them conflates two identity timelines.

→ `vessel_static` is keyed by **mmsi**; `imo` is a nullable, indexed attribute.
Permanent cross-MMSI identity (re-flag / MMSI reuse / spoofing) is modelled
separately and is **optional / phase 2**.

## 3. Data source

The native engine already decodes full JSON (`AIS::JSONAIS json2ais`, fields in
`JSON/Keys.cpp`: `shipname`, `imo`, `callsign`, `shiptype`, `destination`,
`to_bow`…). We must **not** re-parse 6-bit AIS in Kotlin.

The JNI bridge has a `callbackMessage` → Java `AisCatcherJava.onMessage(String)`
hook, but it is **defined and never connected** (`json2ais.out` feeds only the
web server + webviewer). `onMessage` therefore never fires today.

### Chosen source: enable the `onMessage` push (Option A)
Connect `json2ais.out` to a JNI sink that calls `callbackMessage`, so Kotlin
receives one decoded-JSON object per AIS message in real time.

Native delta (in `AIS-catcher-for-Android/.../JNI/AIScatcherNDK.cpp`), ~15 lines:
- Add a `StreamIn<JSON::JSON>` sink whose `Receive()` serializes the JSON and
  calls `callbackMessage(env, str)`.
- After `model->Output() >> json2ais;` (≈line 572) add `json2ais.out >> jniMsgSink;`
- Guard it so it only emits the message types we care about (5, 19, 24) to keep
  JNI traffic low — OR emit all and filter in Kotlin.

> Fork note: this patches `AIS-catcher-for-Android` (upstream jvde-github). Host
> the patch in a maritime-datasystems fork and point the build at it, rather
> than carrying a local-only diff.

### Fallbacks (documented, not chosen)
- **Option B — poll the internal API** on `127.0.0.1:8888` for full ship JSON
  every N s and upsert. No native change, but misses vessels already evicted
  from the engine's RAM table, and the compact `ships_array.json` lacks static
  fields. Weaker.
- **Option C — parse Type 5/24 from `onNMEA` in Kotlin** (already reliably
  delivered). Zero native change, but re-implements multipart 6-bit decoding the
  engine already does. Only if touching native is off-limits.

## 4. Schema

```sql
-- One row per transponder (MMSI). Static identity + latest voyage snapshot.
CREATE TABLE vessel_static (
    mmsi                INTEGER PRIMARY KEY,   -- present in every message
    imo                 INTEGER,               -- Type 5 only; NULL/unknown otherwise
    name                TEXT,                  -- shipname (engine-cleaned)
    callsign            TEXT,
    ship_type           INTEGER,               -- AIS shiptype code
    to_bow              INTEGER,
    to_stern            INTEGER,
    to_port             INTEGER,
    to_starboard        INTEGER,
    -- derived convenience (nullable): length = bow+stern, beam = port+starboard
    -- latest voyage snapshot (semi-static, overwrite with most recent):
    destination         TEXT,
    draught             REAL,
    eta                 TEXT,                  -- raw AIS eta (month/day/hour/min)
    -- bookkeeping:
    msg_types_seen      INTEGER DEFAULT 0,     -- bitmask of static types seen (5/19/24)
    first_seen          INTEGER NOT NULL,      -- epoch ms
    last_static_update  INTEGER NOT NULL,      -- epoch ms, last time static fields changed
    last_seen           INTEGER                -- epoch ms, last message of any type (optional)
);
CREATE INDEX idx_vessel_imo  ON vessel_static(imo);
CREATE INDEX idx_vessel_name ON vessel_static(name);
```

Optional **phase 2** (permanent identity / spoofing analytics — only if needed):
```sql
CREATE TABLE vessel_identity (imo INTEGER PRIMARY KEY, name TEXT, ship_type INTEGER, updated INTEGER);
CREATE TABLE mmsi_assignment (
    mmsi INTEGER, imo INTEGER,
    first_seen INTEGER, last_seen INTEGER, message_count INTEGER,
    PRIMARY KEY (mmsi, imo)               -- here the (mmsi,imo) PAIR is the fact
);
```
The `(mmsi, imo)` pair-table is where a composite key is correct — it records
identity *pairings over time*, surfacing MMSI reuse / re-flag / spoofing. It is
an analytics table, not the position-lookup table.

## 5. Field mapping (AIS message → columns)

| Msg type | Provides | Action |
|---|---|---|
| 5 (Class A static+voyage) | imo, name, callsign, ship_type, dims, destination, draught, eta | upsert all present fields |
| 24A (Class B static, part A) | name | upsert name |
| 24B (Class B static, part B) | callsign, ship_type, dims | upsert those |
| 19 (Class B extended pos) | name, ship_type, dims | upsert those |
| 1/2/3/18/27 (position) | mmsi, lat/lon, sog/cog | update `last_seen` only (no static change) |

**Upsert rules**
- Insert row on first sight of an MMSI (any message), set `first_seen`.
- **Partial update**: only overwrite columns the current message actually
  carries; never null out a known field because a later message omits it
  (e.g. Type 24B must not erase the name from 24A).
- **Never downgrade IMO**: set `imo` only from Type 5 with `imo > 0`; never
  overwrite a known IMO with 0/NULL.
- Bump `last_static_update` only when a static field's value actually changes
  (lets us detect name changes — relevant to existing name-change crawls).

## 6. Storage & write path

- **Tech:** Room (androidx.room 2.6.x) — DAO + compile-time SQL + migrations.
  Add `room-runtime`, `room-ktx`, `ksp` to `app/build.gradle.kts`.
- **DB file:** `databases/vessel_cache.db` in app storage (not in jniLibs).
- **Threading:** `onMessage` runs on the native receiver thread → must not
  block. Push parsed records onto a bounded queue drained by a single-thread
  `room` writer coroutine/executor. Coalesce into transactions (flush every 1 s
  or 200 records). Static-message volume is low (Type 5 ≈ every 6 min/vessel),
  so write pressure is small even in busy waters.
- **Robustness:** wrap JSON parse in try/catch; a malformed message must never
  crash the receiver thread. Reuse the `CopyOnWriteArrayList` listener pattern
  already used for NMEA.

Wiring on the Kotlin side mirrors the existing NMEA listener:
`AisCatcherJava.onMessage` → forward to a `MessageListener` →
`VesselCacheWriter.onMessage(json)` → filter types {5,19,24} → upsert.

## 7. Read / enrichment path

- Expose `VesselCacheDao.getByMmsi(mmsi): VesselStatic?` (indexed PK lookup, sub-ms).
- Consumers:
  - **MQTT publisher / remote payloads:** enrich outgoing data with name/imo/type
    pulled from cache (esp. valuable in the window right after restart).
  - **Map / UI:** optional — the live map is already enriched in-engine; cache
    mainly helps cold start.
- A position arriving for an unknown MMSI simply yields `null` → no enrichment,
  no error; it gets named once any static frame is seen and cached.

## 8. UI / API exposure

- **Wire the dead toggle:** make `internal_db_enabled` actually gate the writer
  (default off → no DB work; on → start `VesselCacheWriter`). Read it in
  `ServiceConfig.fromPreferences` and start/stop the writer in the service
  lifecycle alongside MQTT/FRP.
- **Admin endpoints** (behind existing auth, via `AdminWebServer`):
  - `GET /admin/api/vessels?limit=&q=` — browse/search cached vessels.
  - `GET /admin/api/vessels/count` — row count (cheap health/metric).
  - `GET /admin/api/vessel?mmsi=` — single lookup.
  - (optional) `POST /admin/api/vessels/purge` — clear / apply retention.
- Surface `vessel_count` and `db_size_bytes` in `/admin/api/health` (these are
  non-sensitive; safe even though health is unauthenticated).

## 9. Retention / growth

- Static rows are tiny; even tens of thousands of vessels ≈ a few MB. Still
  unbounded over months → add a configurable policy:
  - keep all (default), or evict rows with `last_seen` older than N days.
- Provide a manual purge endpoint (above). Run eviction lazily (e.g., daily).

## 10. Edge cases

- IMO = 0 / absent → leave `imo` NULL; never part of identity decisions.
- Multipart Type 5 / Type 24 A+B → handled by the engine before JSON; Kotlin
  sees a single decoded object. (Confirm engine emits 24A and 24B as separate
  messages; upsert handles both.)
- Name padding / 6-bit `@` cleanup → already done by `json2ais`; do not re-clean.
- Name change for same MMSI → update + bump `last_static_update`; optionally log
  to feed the existing name-change analysis.
- MMSI reuse / spoofing → out of scope for `vessel_static`; captured by the
  phase-2 `mmsi_assignment` table if enabled.
- Invalid/short MMSI (e.g. base stations 00MIDxxxx, aids-to-nav 99…, SAR) →
  decide whether to store; default store-all, filterable by MMSI class later.

## 11. Migrations / versioning

- Room schema version starts at 1; export schema JSON to `app/schemas` for
  migration diffs. Additive columns only going forward where possible.

## 12. Testing

- Unit: upsert rules (partial update, no-IMO-downgrade, name-change detection)
  with canned decoded-JSON fixtures for types 5/24A/24B/19/1.
- Integration: feed a captured NMEA log through the engine on-device; assert row
  counts and a few known vessels (name/IMO) appear; restart app and confirm rows
  persist and a replayed position enriches immediately.
- Perf: sustained busy-water message rate must not block the receiver thread
  (measure writer queue depth).

## 13. Phased plan

1. **Schema + writer (Option C interim or A):** Room DB, DAO, `VesselCacheWriter`
   wired to a Kotlin message feed; gate on `internal_db_enabled`.
2. **Native push (Option A):** patch `AIScatcherNDK.cpp` to connect
   `json2ais.out` → `onMessage`; switch the writer to consume it. (Fork
   AIS-catcher-for-Android.)
3. **Enrichment:** hook cache reads into MQTT/remote payloads.
4. **Admin API + health counters + UI toggle made functional.**
5. **Retention policy + manual purge.**
6. **(Optional) Phase-2 identity/assignment tables + spoofing signals.**

## 14. Open decisions

- Approve **Option A (native `onMessage` patch)** vs starting with Option C
  (Kotlin NMEA parse, no native change) for phase 1.
- Confirm the on-device cache is a **local cache only**, with TrustedDocks
  remaining the authoritative registry (affects whether phase-2 identity tables
  belong here at all).
- Retention default: keep-all vs evict-after-N-days.
- Whether to store non-ship MMSI classes (base stations, AtoN, SAR).
