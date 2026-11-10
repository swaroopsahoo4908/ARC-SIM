# ARC-SIM

Rocket flight simulation and design toolkit, built on top of the OpenRocket core engine (RK4 integration, Barrowman aerodynamics). Three engines, a rocket builder, and a couple of utility tabs, all in one desktop app.

## Engines

1. *Full Factorial Sweep* — exhaustive grid sweep over wind, turbulence, temperature, pressure, launch rod angle and launch site (145,530 combinations by default). Outputs Parquet plus a CSV summary. Ranges, step counts, targets and named presets (Quick/Standard/Fine) are editable in the GUI or in `sweep_grid.properties`. Every row carries a `grid_index` column. If a run is cancelled or crashes partway, the summary gives a safe resume index and you can continue from it; the new file can be merged with the old one on `grid_index`. Before a run starts, the app times a few real simulations on your machine and prints a runtime estimate. A one-click PDF report is available once it finishes.
2. *Design Solver* — solves ballast mass, fin height, and parachute spill-hole radius against a target apogee/flight-time window, for one fixed atmosphere.
3. *Weather-Driven Design* — pulls live current or forecast weather, then chains the Design Solver and Geometry Exporter together with a local-conditions sensitivity sweep and margin fin-set generation (four margin conditions, solved in parallel, each against its own snapshot of the solved design). Also gets a one-click PDF report when it finishes.

## Utility tabs

Not simulation engines — no target/atmosphere inputs, no Run button:

- *Rocket Builder* — a from-scratch `.ork` editor built directly on OpenRocket core's component model. Tree editor for stages, nose cones, body tubes, transitions, fin sets, internal structure, recovery hardware, ballast — geometry/material/position fields per component, plus a live schematic preview and a live stability-margin readout (calibers, color-coded, updates as you edit). Any body tube or inner tube can be flagged as a motor mount and assigned a real motor from the bundled ~3,300-motor thrust-curve database (`Application.getThrustCurveMotorSetDatabase()`), or a custom one — hand-define a motor from designation/impulse/burn-time/mass specs (idealized trapezoidal thrust curve, exact impulse match) or import a real `.eng`/`.rse`/zipped thrust-curve file. Both register into the live motor database so a saved `.ork` referencing them reloads correctly within that session (custom/imported motors don't survive an app restart — re-define or re-import them if you need to reopen a rocket that uses one). Every material dropdown has a "+" to define a custom material by name and density, which then shows up everywhere for the rest of the session. Full undo/redo (Ctrl+Z / Ctrl+Y) across every edit — geometry, materials, motors, adding/removing/reordering components. New, never-saved rockets default their first Save into `OpenRocket/Rocket Builder/` next to the app, alongside the other tools' output folders. Saves plain `.ork` files usable anywhere else in Arc-Sim — component drag, mass, and small appendages (rail buttons, launch lugs, etc.) all flow straight into every engine's physics automatically, since Arc-Sim hands the real component tree to OpenRocket's own simulation engine. No special-casing needed, and none of it is faked.
- *Geometry Exporter* — exports an STL/OBJ mesh of the rocket's outer shape, including rail buttons and launch lugs as simple protruding boxes (a CAD sanity-check shape, not to spec). Its preview (and Weather-Driven Design's) skips internal mass callouts like ballast/altimeter/recovery hardware to keep the schematic clean — those still show up in Rocket Builder's preview.
- *Data Viewer* — sort/filter/highlight for browsing `.parquet`/`.csv`/`.xlsx` output.

## Wind noise and repeatability

Wind gusts come from OpenRocket's pink-noise model and are random. ARC-SIM fixes the seed so results can be reproduced:

- The sweep derives one seed per grid index from `baseSeed` in `sweep_grid.properties`, so a sweep gives the same numbers every time, on any thread count, and a resumed run matches the original for the same indices.
- The Design Solver and Weather-Driven Design use one fixed seed, which keeps their objective function smooth enough for the solver to converge to 0.1 m.
- The local-conditions sweep draws a fresh seed per sample on purpose.

At 30% turbulence and 5 m/s wind, different seeds move apogee by about 6.5 m (standard deviation, 20 seeds, CSWARCMOD1E), far more than the 0.25 m sweep tolerance. Treat a single seeded pass/fail at high turbulence as one draw of the weather, not a probability.

`windStdDev` is accepted for compatibility but the multi-level wind model does not read it; gust strength comes from `turbulencePct`. `windDir` changes apogee by under 0.05 m because the rod points into the wind, so both axes default to a single value.

## Building and testing

`mvn test` runs 36 tests (unit tests plus end-to-end sweep runs on a 12-combination grid; the physics and sweep tests need `OpenRocket/CSWARCMOD1E.ork` and skip themselves if it is missing). `mvn package` builds the shaded jar.

## Settings file

`.arc-sim-config.properties` holds your weather API key and absolute paths. It is in `.gitignore`; do not commit it.

## ARC V2 CAD template

The Onshape model `ARC_V2_1` (STEP AP242, nose cone, upper tube, mid section, lower body, motor mount, retainer cap, four fins) is the default shape for the 3D views in Engines 2 and 3, the Geometry Exporter and the Rocket Builder. Each preview has an *ARC V2 CAD model* checkbox, and the generators carry their own (*Mesh source* in the Geometry Exporter form, *CAD export mesh* in Engine 3's Output group), so the STL/OBJ files, including Engine 3's main design and margin fin sets, come from whichever source is ticked when the run starts; one setting (`mesh.source` in the app config) drives all four, and unchecking it returns the generated revolve-and-extrude mesh. The preview is software rendered (flat shaded, drag to rotate, wheel to zoom, double-click to reset).

- *Template rocket.* `OpenRocket/ARC_V2_template.ork` is CSWARCMOD1E loaded through the OpenRocket API and edited to the CAD: airframe lengths and radii, ellipsoid nose, trapezoid fins (130 / 55.2 mm chords, 55 mm sweep, 70 mm span), motor mount seated 12 mm forward of the aft face, 10.5 mm motor overhang. Motor configurations, parachute, shock cord, eggs, altimeter and the 132 g ballast come from MOD1E unchanged. Rocket Builder's New Rocket starts from this file.
- *Masses and print material.* Each part starts as CAD volume times PLA Basic density, $\rho = 1.24\ \mathrm{g/cm^3}$ (Bambu Lab, PLA Basic Technical Data Sheet V2.0, ISO 1183), which sums to 332.8 g. Every part is then scaled so the printed parts total the weighed print of the chosen material: *PLA Basic 306 g* (scale 0.9196, effective 1.140 g/cm³) or *PLA Aero 180.6 g* (scale 0.5427 on the raw CAD mass, effective 0.673 g/cm³, which is 0.590 of the Basic print). Bambu lists Aero print density at 0.55 to 1.05 g/cm³ for a 1.21 g/cm³ filament (PLA Aero TDS V4.0), so the Aero figure sits inside the foaming range. Electronics, ballast, motor and recovery gear are not in these totals. Each printed part carries a mass and CG override in the template. The calibration keys (`measuredPrintedMassG`, `measuredPrintedMassAeroG`) are in `src/main/resources/cad/arc_v2_parts.json`.
- *Choosing the material.* Two templates ship: `OpenRocket/ARC_V2_template.ork` (PLA Basic) and `OpenRocket/ARC_V2_template_aero.ork` (PLA Aero). Every engine's rocket-file row has an *ARC V2 template* button that fills in either one. The preview panels have a material box that sets the mass shown and what Rocket Builder's New Rocket starts from (`print.material` in the config). With the F15 and MOD1E's 132 g ballast the still-air apogee is 123.4 m in Basic and 192.6 m in Aero.
- *Transition.* OpenRocket has no S-curve shape; an ogive with parameter 0.026 fits the CAD profile to 0.54 mm RMS.
- *When the CAD is used.* Only if the loaded rocket has the template airframe (nose, tube, transition, tube; section lengths within 0.75 mm, radii within 0.4 mm). Anything else falls back to the generated mesh and the preview says why. Fins always follow the loaded fin set (root, tip, sweep, span, rotation).
- *Regenerating.* `java -cp target/classes:<classpath> com.arc.sim.CadTemplate <MOD1E.ork> <out.ork> [basic|aero] [massScale]`. A `massScale` multiplies on top of the 306 g calibration.
- *Performance note.* Neither print reaches the 243.84 m (800 ft) target on the F15 with the 132 g ballast; Aero is much closer (192.6 m) and has the headroom to get there once the ballast is trimmed.

## Running it

Double-click `ArcSim.app` or `ArcSim.command` (Mac, unsigned — right-click > Open the first time to approve it) or `ArcSim.bat` (Windows). Needs Java 17+, nothing else to install. Self-contained: settings live in a hidden file next to the jar, file dialogs always start in the app's own folder, and per-tab field values (rocket paths, targets, output folders) persist across launches. First launch shows a one-time setup dialog for an optional weather API key.
