# ARC-SIM

Rocket flight simulation and design toolkit, built on top of the OpenRocket core engine (RK4 integration, Barrowman aerodynamics). Three engines, a rocket builder, and a couple of utility tabs, all in one desktop app.

## Engines

1. *Full Factorial Sweep* — exhaustive grid sweep over wind, temperature, pressure, and launch rod angle (~36.7M combinations by default). Outputs Parquet plus a CSV summary. Ranges, step counts, and named presets (Quick/Standard/Exhaustive) are all editable right in the GUI. If a run gets cancelled or crashes partway, you can resume from a checkpoint instead of starting over, and there's a one-click PDF report once it's done.
2. *Design Solver* — solves ballast mass, fin height, and parachute spill-hole radius against a target apogee/flight-time window, for one fixed atmosphere.
3. *Weather-Driven Design* — pulls live current or forecast weather, then chains the Design Solver and Geometry Exporter together with a local-conditions sensitivity sweep and margin fin-set generation (four margin conditions, solved in parallel, each against its own snapshot of the solved design). Also gets a one-click PDF report when it finishes.

## Utility tabs

Not simulation engines — no target/atmosphere inputs, no Run button:

- *Rocket Builder* — a from-scratch `.ork` editor built directly on OpenRocket core's component model. Tree editor for stages, nose cones, body tubes, transitions, fin sets, internal structure, recovery hardware, ballast — geometry/material/position fields per component, plus a live schematic preview and a live stability-margin readout (calibers, color-coded, updates as you edit). Any body tube or inner tube can be flagged as a motor mount and assigned a real motor from the bundled ~3,300-motor thrust-curve database (`Application.getThrustCurveMotorSetDatabase()`), or a custom one — hand-define a motor from designation/impulse/burn-time/mass specs (idealized trapezoidal thrust curve, exact impulse match) or import a real `.eng`/`.rse`/zipped thrust-curve file. Both register into the live motor database so a saved `.ork` referencing them reloads correctly within that session (custom/imported motors don't survive an app restart — re-define or re-import them if you need to reopen a rocket that uses one). Every material dropdown has a "+" to define a custom material by name and density, which then shows up everywhere for the rest of the session. Full undo/redo (Ctrl+Z / Ctrl+Y) across every edit — geometry, materials, motors, adding/removing/reordering components. New, never-saved rockets default their first Save into `OpenRocket/Rocket Builder/` next to the app, alongside the other tools' output folders. Saves plain `.ork` files usable anywhere else in Arc-Sim — component drag, mass, and small appendages (rail buttons, launch lugs, etc.) all flow straight into every engine's physics automatically, since Arc-Sim hands the real component tree to OpenRocket's own simulation engine. No special-casing needed, and none of it is faked.
- *Geometry Exporter* — exports an STL/OBJ mesh of the rocket's outer shape, including rail buttons and launch lugs as simple protruding boxes (a CAD sanity-check shape, not to spec).
- *Data Viewer* — sort/filter/highlight for browsing `.parquet`/`.csv`/`.xlsx` output.

## Running it

Double-click `ArcSim.app` or `ArcSim.command` (Mac, unsigned — right-click > Open the first time to approve it) or `ArcSim.bat` (Windows). Needs Java 17+, nothing else to install. Self-contained: settings live in a hidden file next to the jar, file dialogs always start in the app's own folder, and per-tab field values (rocket paths, targets, output folders) persist across launches. First launch shows a one-time setup dialog for an optional weather API key.
