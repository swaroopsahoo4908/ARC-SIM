# ARC-SIM


Rocket flight simulation and design toolkit built on the OpenRocket core engine (RK4 integration, Barrowman aerodynamics). Four engines, one desktop app.

## Engines

1. *Full Factorial Sweep* — exhaustive grid sweep over wind, temperature, pressure, and launch rod angle (~36.7M combinations by default). Outputs Parquet + CSV summary.
2. *Design Solver* — solves ballast mass, fin height, and parachute spill-hole radius against a target apogee/flight-time window for one fixed atmosphere.
3. *Geometry Export* — exports STL/OBJ mesh of the rocket's outer shape.
4. *Weather-Driven Design* — pulls live weather, then chains Engines 2 and 3 plus a local-conditions sensitivity sweep and margin fin-set generation.

Plus a *Data Viewer* tab for browsing `.parquet`/`.csv`/`.xlsx` output.

## Running it

Double-click `ArcSim.command` (Mac) or `ArcSim.bat` (Windows) — needs Java 17+, nothing else to install. Self-contained: settings live in a hidden file next to the jar, file dialogs always start in the app's own folder. First launch shows a one-time setup dialog for an optional weather API key.

