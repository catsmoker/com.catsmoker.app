# Metrics Engine

`MetricsEngine` (`features/main/engine/MetricsEngine.kt`) powers the home
dashboard telemetry and the performance overlay. This page documents what it
measures, where each number comes from, and the careful status model that
keeps the UI honest.

## What it measures

| Metric                     | Primary source                                 | Privilege needed              |
| -------------------------- | ---------------------------------------------- | ----------------------------- |
| FPS                        | `dumpsys SurfaceFlinger --timestats`           | Yes                           |
| FPS (fallback)             | this process's `Choreographer` vsync callbacks | No                            |
| Missed frames              | `SurfaceFlinger --timestats`                   | Yes                           |
| Frame-time p95/p99 (ms)    | `SurfaceFlinger --timestats` histograms        | Yes                           |
| CPU total                  | delta between two `/proc/stat` samples         | Root/Shizuku or readable proc |
| CPU total (fallback)       | `top -n 1 -b`, then `dumpsys cpuinfo`          | Yes                           |
| Top process (CPU)          | same `top` output                              | Yes                           |
| RAM                        | `/proc/meminfo` → `ActivityManager` fallback   | No                            |
| Battery temp / level       | sticky `ACTION_BATTERY_CHANGED`                | No                            |
| Power draw (W)             | `BATTERY_PROPERTY_CURRENT_NOW` × voltage       | No                            |
| Thermal (CPU/GPU/skin/NPU) | `ThermalServiceParser` over sysfs/dumpsys      | Varies                        |
| GPU utilization / clock    | vendor sysfs (`GpuTelemetryReader`)            | Root/Shizuku on locked kernels |
| Ping                       | `ping -c 1 -W 2 8.8.8.8`                       | No                            |
| Network Rx/Tx              | `TrafficStats` deltas                          | No                            |

The GPU pair is **overlay-only** (developer decision, 2026-10-20): the home
dashboard's 2×2 grid is full and has no spare slot, so `MetricsState` carries
the reading for the performance overlay's `GPU` row, and the dashboard does not
show it.

## Status model: honesty over invention

Every reading is paired with a `MetricReadStatus`, so the UI can tell a real
number from a gap. The guiding rule: **never render a manufactured zero** —
a device at `0 GB` RAM, `0 ms` ping, or `0 %` CPU does not exist, and a
"stale" value must not be presented as a fresh reading.

Key behaviors:

- **FPS** — SurfaceFlinger stats accumulate until cleared, so the average is
  only meaningful over a window. The engine clears the window in the first
  second of each 3 s cycle, guaranteeing ~2 s of accumulated frames before the
  next dump. An empty dump right after a clear holds the last real value
  (`FPS_STALE_TOLERANCE` dumps) rather than publishing `0`. The `Choreographer`
  fallback is used only when no privileged channel is available, and is always
  tagged `FpsSource.Choreographer` because it measures the *display/app* rate,
  not the foreground game's rate.
- **Frame-time p95/p99** — the same dump's `present2present` histogram (first
  layer after an `averageFPS`) or the global `presentToPresent` histogram,
  reduced by `SurfaceFlingerFrameTimeParser` to the bucket the 95th and 99th
  frame of the window landed in. They share `fpsReadStatus` (same dump, same
  privilege, same gap), are held together with the held FPS so the pair always
  describes one window, and are `null` on the Choreographer fallback — an
  average hides stutter, and a manufactured percentile would hide it again.
  The overlay's `P95/P99` row prints them only as a pair.
- **CPU** — a single `/proc/stat` sample is not a reading (the file holds
  cumulative jiffies since boot); the first sample only establishes a baseline
  and is reported `Loading`, not `0`.
- **RAM** — prefers `MemAvailable` (the kernel's own estimate), falling back
  to `MemFree + Buffers + Cached` on pre-3.14 kernels, and to `ActivityManager`
  if the file is unreadable. Reports nothing rather than `0 GB`.
- **Power** — takes `CURRENT_NOW` (µA) × voltage (mV). A stubbed/unsupported
  HAL answers `0` or `Integer.MIN_VALUE`; a wrong-unit build produces a figure
  far outside the plausible band (`0.02 W`–`150 W`). Both are discarded and
  logged as *no value* rather than silently rescaled into range.
- **Ping** — a run with no `time=` means "no reply", reported as no reading,
  not `0 ms`.
- **GPU** — no public Android API reports GPU utilization or clock, so the only
  reading surface is the vendor's own sysfs (`kgsl` for Qualcomm, `mali0` +
  devfreq for Mali, GED for MediaTek). The reader tries plain file I/O first and
  falls back to one batched privileged `cat` for every candidate node when the
  app holds root/Shizuku — on the lab POCO X3 the nodes exist and are
  world-readable *by mode*, but SELinux denies both the app UID and `shell`, so
  the row says "needs root/Shizuku" rather than showing a dash. No value with no
  privilege is `PrivilegeDenied`, no node text with privilege is
  `Unsupported` (a kernel without the vocabulary), text that reads but parses to
  nothing is `ParseFailed`. Units are declared per node by the reader table
  (`clock_mhz` is MHz, `gpuclk`/devfreq Hz, GED kHz) — never guessed from the
  value — a percentage outside 0..100 and a clock outside 1 MHz–10 GHz are
  discarded as unit mistakes, and the `gpubusy` counter pair yields nothing when
  its total is 0 (a kernel that never filled it). The overlay row prints
  utilization and clock together (`34% 490MHz`); either half may stand alone,
  the status explains a fully missing pair.

## Polling design

`start()` launches several staggered, independent loops on `Dispatchers.IO`
so a single bad reading (or a slow shell) can't stall or kill the rest:

| Loop                                        | Interval | Stagger |
| ------------------------------------------- | -------- | ------- |
| Privileges & status                         | 5 s      | 0       |
| Heavy (top processes, detailed CPU)         | 10 s     | 1 s     |
| System stats (RAM, battery, power, network, GPU, CPU clusters) | 5 s | 0 |
| High-cadence (CPU delta)                    | 4 s      | 500 ms  |
| Background shell (thermal, ping)            | 10 s     | 2 s     |
| FPS channel selector                        | 1 s      | 1 s     |

Each `pollLoop` body is wrapped in `guarded()`, which swallows *any*
`Throwable` (catching even an `ExceptionInInitializerError` from a parser —
one bad regex used to take the whole process down) but rethrows
`CancellationException` so `stop()` still tears the loop down cleanly.

The FPS channel is re-selected every tick because privilege can arrive after
start-up (the user may grant Shizuku at the dialog); SurfaceFlinger is
preferred whenever it becomes reachable.

## History / sparklines

Each metric keeps a bounded history (cap `HISTORY_CAP = 60`) pushed as fresh
immutable lists so Compose recomposes. Only *real* readings enter the
sparklines — a missing sample would otherwise be drawn as a dip to zero.
History is stored in `MutableStateFlow` per metric (`fpsHistory`, `cpuHistory`,
`ramHistory`, `tempHistory`, `pingHistory`).

## Stopping

`stop()` cancels the scope and resets transient state (choreographer flags,
timestats, stale-FPS counters, the held frame-time percentiles) so a later
`start()` works from a clean slate.

## Parser package

`features/main/engine/parsers/` holds pure-Kotlin, unit-tested parsers:

- `CpuStatParser` — `/proc/stat` totals and per-interval CPU usage.
- `CpuInfoTopParser` — total and per-process CPU from `top`/`dumpsys cpuinfo`.
- `ThermalServiceParser` — CPU/GPU/skin/NPU temps from the thermal service.
- `SurfaceFlingerFrameTimeParser` — p95/p99 frame-time percentiles from the
  `--timestats -dump` histograms (per-layer `present2present` preferred, global
  `presentToPresent` fallback, nearest-rank, null when no histogram is present).
- `GpuTelemetryParser` — GPU utilization (`gpu_busy_percentage`, the `gpubusy`
  counter pair, Mali `utilization`, GED `gpu_utilization`) and core clock in
  MHz from a caller-declared unit; total and plausibility-bounded, so a text
  that is not a reading yields null. `GpuTelemetryReader` owns the node table
  and the batched privileged fallback.

The analog `gamingtools/engine/parsers/DexoptStatusParser` parses
`dumpsys package dexopt` for the ART optimizer.
