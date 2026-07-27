# ARC-SIM


Rocket flight simulation and design toolkit built on the OpenRocket core engine (RK4 integration, Barrowman aerodynamics). Four engines, one desktop app.

## Engines

1. *Full Factorial Sweep* — exhaustive grid sweep over wind, temperature, pressure, and launch rod angle (~36.7M combinations by default). Outputs Parquet + CSV summary. Editable ranges/step-counts and named presets (Quick/Standard/Exhaustive) right in the GUI, resume-from-checkpoint after a cancelled or interrupted run, and a one-click PDF report on completion.
2. *Design Solver* — solves ballast mass, fin height, and parachute spill-hole radius against a target apogee/flight-time window for one fixed atmosphere.
3. *Geometry Export* — exports STL/OBJ mesh of the rocket's outer shape.
4. *Weather-Driven Design* — pulls live current or forecast weather, then chains Engines 2 and 3 plus a local-conditions sensitivity sweep and margin fin-set generation (four margin conditions solved in parallel, each against its own snapshot of the solved design). One-click PDF report on completion.

Plus a *Data Viewer* tab (sort/filter/highlight) for browsing `.parquet`/`.csv`/`.xlsx` output.

## Running it

Double-click `ArcSim.app` or `ArcSim.command` (Mac, unsigned — approve via right-click > Open the first time) or `ArcSim.bat` (Windows) — needs Java 17+, nothing else to install. Self-contained: settings live in a hidden file next to the jar, file dialogs always start in the app's own folder, and per-tab field values (rocket paths, targets, output folders) persist across launches. First launch shows a one-time setup dialog for an optional weather API key.

