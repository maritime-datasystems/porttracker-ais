# Spec: Vessel position history with tiered retention

Status: draft · Target: porttracker-ais

## 1. Goal

Persist each vessel's **dynamic** reports — position, speed, course, heading,
draught, timestamp — into the SQLite DB, so the station keeps a local track
history per vessel.

Retention tiers:
- **Raw** points kept for **3 months**.
- Older than 3 months → **downsampled to hourly**.
- Older than ~1 year → **downsampled to daily**.

This complements the existing `vessel_static` cache (identity) with a
time-series of where each vessel was. Static draught is already stored as the
*latest* value in `vessel_static`; here draught is also recorded per track point
so its change over a voyage is preserved.

## 2. The volume problem (why dedup is required)

The writer polls the engine every 30 s. Storing every poll for every vessel:
- ~150 vessels × 2/min = ~300/min ≈ **430k rows/day** ≈ **39M rows / 90 days**
- ~60 bytes/row ≈ **~2.3 GB** of raw data on the phone.

Most of that is redundant — moored vessels reporting the same position every 30 s.
So points are stored **only when meaningful** (movement-based ingestion), which
cuts moored-vessel rows ~10× and brings 90-day raw storage to **~1 GB**, then
downsampling shrinks the long tail.

> Decision: on-device is what was requested. Note the authoritative long-term
> history arguably belongs server-side (TrustedDocks ingest from MQTT); the
> on-device store is a local track log. Keep the retention/downsampling so it
> never grows unbounded.

## 3. Schema

```sql
CREATE TABLE vessel_position (
    mmsi        INTEGER NOT NULL,
    ts          INTEGER NOT NULL,   -- epoch ms (reception time)
    lat         REAL NOT NULL,
    lon         REAL NOT NULL,
    sog         REAL,               -- speed over ground (knots)
    cog         REAL,               -- course over ground (deg)
    heading     INTEGER,            -- true heading (deg, NULL if 511/NA)
    draught     REAL,               -- metres (latest known at this time)
    nav_status  INTEGER,            -- AIS navigational status (optional)
    resolution  INTEGER NOT NULL DEFAULT 0,  -- 0=raw, 1=hourly, 2=daily
    PRIMARY KEY (mmsi, ts)
);
CREATE INDEX idx_pos_ts        ON vessel_position(ts);              -- retention sweeps
CREATE INDEX idx_pos_mmsi_ts   ON vessel_position(mmsi, ts);       -- per-vessel track
CREATE INDEX idx_pos_res_ts    ON vessel_position(resolution, ts); -- downsample passes
```

Pragmas on open: `journal_mode=WAL`, `synchronous=NORMAL` (durability vs write
throughput trade-off acceptable for a track log).

DB version bumps 1 → 2 with an additive migration (new table + indexes).

## 4. Ingestion (movement-based)

Extend `VesselCacheWriter` (already polling `/api/ships.json` every 30 s). Keep
an in-memory `lastStored[mmsi] = {ts, lat, lon, cog}`. For each vessel with a
valid position, append a raw row when **any** holds:

- no prior stored point, OR
- moved > **MOVE_THRESHOLD_M** (default 50 m) from last stored, OR
- elapsed > **HEARTBEAT_SEC** (default 300 s) since last stored — so moored
  vessels still get a periodic point, OR
- |Δcog| > **TURN_DEG** (default 15°) — capture manoeuvres.

All new points for a poll are inserted in one transaction. `draught`,
`nav_status` taken from the same decoded snapshot.

Tunables exposed as constants (later: prefs).

Estimated steady-state: moving vessels ~1 pt/30 s, moored ~1 pt/5 min →
~120 rows/min ≈ **170k/day** ≈ **15M / 90 days** (~1 GB).

## 5. Retention & downsampling (daily maintenance)

A once-a-day maintenance pass (guarded by a `last_maintenance_ms` pref so it
runs ~every 24 h, on a background thread, in batches). It processes the
**sliding boundary**, not the whole table, so each run is cheap:

1. **Raw → hourly** for rows crossing the 90-day boundary:
   - For each `(mmsi, hour_bucket)` with `resolution=0` and `ts < now-90d`,
     keep one representative row (the **last point in the hour** — a real
     reported position, not a synthetic average) → set `resolution=1`,
     delete the other raw rows in that bucket.
2. **Hourly → daily** for rows crossing the ~365-day boundary:
   - Same decimation per `(mmsi, day_bucket)` → `resolution=2`.
3. (Optional) purge `resolution=2` older than a configurable max (e.g. 2 y),
   or keep forever (daily rows are tiny).

Decimation (keep-representative) is chosen over averaging: it preserves actual
AIS-reported positions and is far cheaper than computing averages on-device.

Batching: delete in chunks (`LIMIT N` loops) to bound transaction size and
avoid long locks while the writer is inserting.

> Scheduling: a lightweight in-service daily check (pref timestamp) avoids a
> new dependency. WorkManager is the more robust alternative if we want it to
> run even when the app/service isn't up — propose deferring unless needed.

## 6. API / consumption

- `GET /admin/api/vessel/track?mmsi=<n>&from=<ms>&to=<ms>&res=<0|1|2>`
  → JSON array of `{ts,lat,lon,sog,cog,heading,draught}` for plotting.
- `GET /admin/api/positions/stats` → counts per resolution + DB size bytes.
- `GET /admin/api/vessel/track.csv?mmsi=` → CSV export of a track.
- DB page: show position-row count + DB size; per-vessel detail page gets a
  "Track" section / mini-map.
- Map: clicking a vessel can draw its recent track as a polyline (phase 4).

## 7. Config / UX

- New toggle **`position_logging_enabled`** (separate from `internal_db_enabled`
  because it is much heavier on storage). Default **off**.
- Optional prefs: retention days (default 90), movement thresholds.
- Surface DB size and a **Purge positions** action so the user can reclaim space.

## 8. Performance & safety

- Writes: 30 s batches, WAL, single writer thread → no contention with reads.
- Reads (track API): indexed by `(mmsi, ts)`; cap returned points (e.g. 5000)
  and prefer a coarser `resolution` for long ranges.
- Maintenance runs off the UI/native threads, batched, only on the sliding
  boundary.
- Guard against clock changes (use reception time from the engine where
  available; `ts` monotonic-ish).

## 9. Edge cases

- Vessel with no position (static only) → no row.
- Bad/zero coordinates (0,0) → skip.
- Heading 511 (N/A) → store NULL.
- Duplicate `ts` for same mmsi (two polls same ms) → PK conflict; ignore/skip.
- Very large tracks in the API → enforce `res`/`limit`.
- Storage pressure → a hard cap (e.g. max DB size) that forces earlier
  downsampling or oldest-purge, with a `log()`-style warning.

## 10. Phased plan

1. **Schema + ingestion** (movement-based, draught) gated by
   `position_logging_enabled`; DB migration v2.
2. **Track API** (`/vessel/track`, `/positions/stats`) + DB-page size/row stats.
3. **Daily maintenance**: raw→hourly→daily downsampling + retention, batched.
4. **Map/detail track overlay** (polyline of recent track) + CSV export.
5. (Optional) server-side offload to TrustedDocks; storage-cap safeguards.

## 11. Open decisions (need your call)

- **Ingestion**: movement-based dedup (~1 GB/90 d, recommended) vs store every
  30 s poll (~2–3 GB+).
- **Downsample method**: decimation/keep-representative (recommended) vs
  averaging positions.
- **Toggle**: separate `position_logging_enabled` (recommended) vs reuse
  `internal_db_enabled`.
- **Daily-tier cutoff**: when hourly → daily (proposed ~1 year) and whether to
  ever purge daily.
- **Scheduling**: in-service daily check (simple) vs WorkManager (robust).
