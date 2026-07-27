# ARC-SIM


Rocket flight simulation and design toolkit built on the OpenRocket core engine (RK4 integration, Barrowman aerodynamics). Three engines, a rocket builder, and a couple of utility tabs, one desktop app.

## Engines

1. *Full Factorial Sweep* — exhaustive grid sweep over wind, temperature, pressure, and launch rod angle (~36.7M combinations by default). Outputs Parquet + CSV summary. Editable ranges/step-counts and named presets (Quick/Standard/Exhaustive) right in the GUI, resume-from-checkpoint after a cancelled or interrupted run, and a one-click PDF report on completion.
2. *Design Solver* — solves ballast mass, fin height, and parachute spill-hole radius against a target apogee/flight-time window for one fixed atmosphere.
3. *Weather-Driven Design* — pulls live current or forecast weather, then chains the Design Solver and Geometry Exporter plus a local-conditions sensitivity sweep and margin fin-set generation (four margin conditions solved in parallel, each against its own snapshot of the solved design). One-click PDF report on completion.

## Utility tabs

Not simulation engines — no target/atmosphere inputs, no Run button:

- *Rocket Builder* — a from-scratch `.ork` editor built directly on OpenRocket core's component model: a tree editor for stages, nose cones, body tubes, transitions, fin sets, internal structure, recovery hardware, and ballast, with geometry/material/position fields per component and a live schematic preview. Any body tube or inner tube can be flagged as a motor mount and assigned a real motor from the bundled ~3,300-motor thrust-curve database (`Application.getThrustCurveMotorSetDatabase()`), or a custom one: hand-define a motor from designation/impulse/burn-time/mass specs (idealized trapezoidal thrust curve, exact impulse match) or import a real `.eng`/`.rse`/zipped thrust-curve file — both register into the live motor database so a saved `.ork` referencing them reloads correctly *within that session* (custom/imported motors don't persist across an app restart; re-define or re-import them if needed). Every material dropdown has a "+" to define a custom material (name + density) that then appears everywhere for the rest of the session. New, never-saved rockets default their first Save into `OpenRocket/Rocket Builder/` next to the app, alongside the other tools' output-category folders. Saves plain `.ork` files usable anywhere else in Arc-Sim — component drag, mass, and small appendages (rail buttons, launch lugs, etc.) all flow straight into every engine's physics automatically since Arc-Sim hands the real component tree to OpenRocket's own simulation engine, no special-casing needed.
- *Geometry Exporter* — exports STL/OBJ mesh of the rocket's outer shape.
- *Data Viewer* — sort/filter/highlight for browsing `.parquet`/`.csv`/`.xlsx` output.

## Running it

Double-click `ArcSim.app` or `ArcSim.command` (Mac, unsigned — approve via right-click > Open the first time) or `ArcSim.bat` (Windows) — needs Java 17+, nothing else to install. Self-contained: settings live in a hidden file next to the jar, file dialogs always start in the app's own folder, and per-tab field values (rocket paths, targets, output folders) persist across launches. First launch shows a one-time setup dialog for an optional weather API key.

