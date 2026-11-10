# Changelog

## Unreleased

### Added
- ARC V2 CAD template (Onshape `ARC_V2_1`, STEP AP242) as the default 3D shape for the Engine 2 and 3 previews, the Geometry Exporter and the Rocket Builder. A shared *ARC V2 CAD model* toggle (`mesh.source`) switches back to the generated mesh; the Geometry Exporter and Engine 3 output groups carry their own checkbox, which covers the STL/OBJ for the main design and the margin fin sets.
- `OpenRocket/ARC_V2_template.ork` (PLA Basic, weighed 306 g) and `ARC_V2_template_aero.ork` (PLA Aero, weighed 180.6 g): CSWARCMOD1E edited to the CAD, per-part masses scaled from the CAD volumes to the weighed totals. Every engine's rocket-file row has an *ARC V2 template* menu for both, the preview panels have a print-material box, and Rocket Builder's New Rocket starts from the chosen one.
- The CAD is used only when the loaded rocket has the template airframe; otherwise the generated mesh is used and the preview says why. Fins follow the loaded fin set.
- `CadModelTest` (10 tests).

## 1.2.0 (2026-11-08)

### Fixed
- Wind shear profile passed speed and direction to OpenRocket's `addWindLevel` in the wrong order (it takes altitude, speed, direction, stddev). Since commit aa3a38d (2026-07-30) every simulation that used the shear profile had an effective wind speed equal to the wind direction in radians (0 to 6.1 m/s), while the real wind speed only set a direction. Apogee therefore drifted with `windDir` (about 30 m between 0 and 350 degrees at 11 m/s) and barely responded to real wind speed. Results produced with the app after that commit are not valid and should be re-run: `Engine 3/..._20260802_173320`, `Engine 3/..._20260903_153133`, `Full Factorial/..._20260827_*`, and any solve made with the app after 2026-07-30.
- Gusts were different on every run: OpenRocket gives each wind level a fresh random seed and `setRandomSeed` never reaches them. Added `WindSeeder`, which pins them. Same inputs and seed now give a bit-identical flight.
- Sweep writer blocked forever if a worker thread died with an exception. It now stops, writes what it has, and rethrows the failure.
- Safe resume index only counted rows produced, not rows written, and was dragged back to the start of thread 0's chunk. Work is now handed out in blocks of 256 indices and the index advances only past blocks fully on disk. Cancelling also writes the results still in the queue.
- `GridAxis.count()` could yield a last value above `max` (0..8 step 5 gave 0, 5, 10) and turned a zero step into Integer.MAX_VALUE. It now floors, caps at `max`, and rejects invalid axes.
- Engine numbering in console output and `Main` usage text now matches the README.

### Added
- `grid_index` column in sweep Parquet output so partial and resumed runs can be merged.
- Calibrated runtime estimate: times 24 real simulations before a sweep and in the GUI Preview button, replacing the fixed 30 ms guess.
- `sweep_grid.properties` keys: `targetApogeeM`, `apogeeToleranceM`, `targetTimeS`, `timeToleranceS`, `baseSeed`, `maxHoursSafety`.
- 36 tests (`mvn test`), including regression tests for the wind-argument bug, the worker-failure hang, resume correctness, and run-to-run repeatability.

### Changed
- Default grid trimmed from 36,673,560 to 145,530 combinations: `windStdDev` (not read by the multi-level wind model) and `windDir` (under 0.05 m effect with launch-into-wind) default to one value. GUI presets are now Quick (675/site), Standard (72.8k/site), Fine (1.6M/site).
- `.gitignore` now excludes `.arc-sim-config.properties`, `target/` and `dependency-reduced-pom.xml`.
