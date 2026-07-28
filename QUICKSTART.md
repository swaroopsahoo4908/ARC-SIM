# Arc-Sim Quickstart Guide

A plain-language walkthrough for getting Arc-Sim running and using it for the first time. For the technical details on how each engine works internally, see `README.md`.

## 1. Requirements

- A Java 17 (or newer) runtime. If you don't already have one, grab the free Eclipse Temurin build for your OS from [adoptium.net](https://adoptium.net) and install it first.

## 2. Installing

You should have a folder with at least these files in it:

| File | Purpose |
|---|---|
| `ArcSim.jar` | The application itself — everything's bundled inside, nothing else to install |
| `ArcSim.app` | Double-click app-bundle launcher for macOS (nicer icon/dock behavior than the `.command` script; needs to stay next to `ArcSim.jar`) |
| `ArcSim.command` | Terminal-script launcher for macOS |
| `ArcSim.bat` | Double-click launcher for Windows |
| `sweep_grid.properties` | Default configuration for Engine 1 (Full Factorial Sweep) |
| `QUICKSTART.md` | This guide |
| `README.md` | Technical/engineering documentation |

Copy the whole folder anywhere convenient — Desktop, Documents, an external drive, doesn't matter, nothing's hardcoded to one location. Just keep all the files above together in the same folder, since the launchers expect `ArcSim.jar` to sit right next to them. Arc-Sim only ever reads and writes files inside this folder (your `.ork` files, run output, and a hidden settings file, `.arc-sim-config.properties`, written next to `ArcSim.jar`). Nothing gets installed or written anywhere else on your machine, so feel free to move, copy, rename, or re-zip the whole folder — it keeps working wherever it lands.

*macOS*: double-click `ArcSim.app` (or `ArcSim.command` if you'd rather see a Terminal window). Neither one is code-signed or notarized — that requires a paid Apple Developer account — so the first time you open either, macOS will refuse and call it "unidentified developer." Right-click it and choose *Open* once, confirm, and after that it opens normally with a plain double-click from then on.

*Windows*: double-click `ArcSim.bat`.

If either launcher complains that Java wasn't found or is too old, go install/update it from [adoptium.net](https://adoptium.net) — grab the "JRE" or "JDK" build for your OS, version 17 or newer — and try again.

## 3. First-run setup

The first time Arc-Sim starts, a one-time setup window pops up with a single optional field:

- *Weather API key (optional)*: only needed if you want to use Engine 4 (Weather-Driven Design) or the "Use Current Location" button, both of which pull live weather data. Get a free key at [weatherapi.com](https://www.weatherapi.com) if you want it. You can skip this for now and add it later (Section 6, Settings).

Click *Get Started*. This only happens once.

Every file dialog in the app (open `.ork`, grid config, output folders, Data Viewer) starts in the same folder as `ArcSim.jar`/`ArcSim.command` on each launch, then follows you to wherever you browse next for the rest of that session. There's no separate working-folder setting to configure or accidentally get out of sync with where you actually keep the app.

## 4. The three engines (plus Rocket Builder, Geometry Exporter, and Data Viewer)

Arc-Sim opens with one tab per engine, plus three utility tabs: Rocket Builder, Geometry Exporter, and Data Viewer. Each engine tab has its own form, a *Run* button, a progress bar, and a live log at the bottom of the window.

1. *Engine 1 — Full Factorial Sweep*: point it at a `.ork` rocket file and it exhaustively tests every combination of wind, temperature, pressure, launch angle, and launch site in a grid, producing a full results table (`.parquet` plus a `.csv` summary). Each swept parameter gets its own row in the tab — *range1* to *range2* at *step count*, editable right in the GUI, no need to hand-edit a config file. A *Grid preset* dropdown (Quick/Standard/Exhaustive) fills in all seven rows at once as a starting point. Sites use the same picker as Engines 2/4 (check any combination of the two presets and/or Custom lat/lon/alt, with a "Use Current Location" button) — every checked site gets swept as its own axis. The safety cap and thread count still live in `sweep_grid.properties` (click "Edit sites/safety cap..."); "Load ranges from file" / "Save ranges to file" sync the on-screen ranges and sites with that file if you want to reuse a grid later. Worth clicking *Preview combination count & time estimate* first — it tells you how long a run will actually take before you commit to it. If a run gets cancelled or fails partway through, the *Resume from index* field auto-fills with a safe restart point, so clicking *Run* again picks up where it left off instead of starting over (this writes a second output file covering just the remainder). Once a run finishes, *Generate PDF Report* turns it into a short, shareable summary — run stats plus any conditions that met both targets.
2. *Engine 2 — Design Solver*: give it a target apogee and flight-time window plus one fixed set of conditions, and it solves for ballast weight, fin height, and parachute spill-hole size to hit those targets, saving a new `.ork` file with the solved design.
3. *Engine 4 — Weather-Driven Design*: pulls live current or forecast weather for a launch site (set a future date/hour and click *Fetch Forecast* to plan ahead of a launch day, within your weatherapi.com plan's forecast window) and runs an end-to-end pipeline against it in one click — solve, export, sensitivity check, spare fin sets, solved in parallel. Needs the weather API key from setup. *Generate PDF Report* is available once a run finishes.

And the utility tabs (not simulation engines — no target/atmosphere inputs, no Run button):

- *Rocket Builder*: a from-scratch `.ork` editor. Add, remove, and reposition components (nose cones, body tubes, transitions, fin sets, parachutes, mass components, and more), edit their geometry and materials, assign a motor to a motor mount from the bundled thrust-curve database, and save the result as a new `.ork` file — all without leaving Arc-Sim.
  - *Stability readout*: a live caliber margin shows above the preview as you build, color-coded (unstable/marginal/stable/overstable), along with the CG, CP, and mass it's computed from.
  - *Undo/redo*: every edit — geometry, material, position, motor assignment, adding or removing a component — can be undone and redone, via the toolbar buttons or Ctrl+Z / Ctrl+Y.
  - *Custom materials*: click the "+" next to any material dropdown to define one by name and density — it's then available in every material dropdown for the rest of the session.
  - *Custom motors*: in the motor picker (click "Select Motor..." on any motor mount), either *Create Custom Motor...* (hand-enter designation, diameter, length, burn time, total impulse, masses, and delays — builds an idealized thrust curve matching your numbers exactly) or *Import Motor File (.eng/.rse)...* to load a real thrust-curve file. Both show up in a "Custom / Imported Motors" table in the picker alongside the bundled database. These only stick around for the current session — if you restart Arc-Sim, re-define or re-import a custom motor before opening a rocket that uses one, or its saved `.ork` won't be able to reload the motor.
  - The first time you save a brand-new rocket, "Save As" defaults into `OpenRocket/Rocket Builder/` next to the app, matching the other tools' output folders.
  - Everything you build here — including small appendages like rail buttons and launch lugs, which also show up in the preview and the CAD export now — feeds straight into every other engine's physics with no extra steps, since Arc-Sim runs the same real OpenRocket component tree and simulation engine everywhere.
- *Geometry Exporter*: exports a 3D-printable/CAD-importable mesh (STL/OBJ) of your rocket's outer shape, for a quick physical mockup or CAD sanity check.

Every run writes its output into a clearly named subfolder/file (there's always a timestamp baked into the filename), so re-running never clobbers a previous result. Every field you fill in — rocket file paths, targets, output folders, etc. — is remembered per-tab between launches, so you're not re-browsing to the same files every session.

## 5. A typical first session

1. Open the *Engine 1* tab.
2. Browse to your `.ork` rocket file.
3. Adjust any of the seven range rows (wind avg, wind std dev, turbulence, wind direction, temp, pressure, rod angle) if the defaults don't fit your case — each is *min* to *max* at *step count*.
4. Click *Preview combination count & time estimate* and read the printed estimate in the log.
5. If that estimate is longer than you want to wait, lower a step count or narrow a range, then preview again.
6. Click *Run Full Factorial Sweep*. Watch progress in the log and the live leaderboard of best-matching conditions found so far.
7. When it finishes, open the *Data Viewer* tab to browse the results — type in the filter box to search any column, sort by clicking a column header, rows meeting both your targets get highlighted automatically. Or open the output `.parquet`/`.csv` files directly in any Parquet-aware tool (pandas, DuckDB, etc.) or a text editor for the CSV summary. Or just click *Generate PDF Report* back on the Engine 1 tab for a one-page summary you can hand off as-is.

## 6. Settings

Want to add or change the weather API key later? Use *File > Preferences* at any time — no restart needed.

*Help > About Arc-Sim* shows the installed version. *Help > Open Quickstart Guide* reopens this document.

## 7. Troubleshooting

- *Launcher does nothing / "unidentified developer" warning (Mac)*: right-click `ArcSim.app` (or `ArcSim.command`) and choose *Open* once to approve it, per Section 2. Neither is code-signed.
- *"No Java runtime was found"*: install Java 17+ from [adoptium.net](https://adoptium.net).
- *A run refuses to start, citing a "safety cap"*: your grid config would produce more combinations than the configured safety limit. Either coarsen the grid (see Section 5, step 5), raise `maxCombosSafety` in the config file, or check *Force* in the Engine 1 tab if you're sure you want to go ahead anyway.
- *Weather features are greyed out or fail*: add a weather API key via *File > Preferences* (Section 3).
- *"Use Current Location" resolved to a city that's clearly not where you are*: this is expected if the on-device location tool isn't available (see the app's console/log output for exactly why — usually a missing `CoreLocationCLI` install on Mac) and it fell back to IP-based geolocation, which is only city/metro-level accurate at best, sometimes off by a hundred-plus kilometers depending on your ISP. Use Custom lat/lon instead if you need real precision, or confirm against a handheld GPS reading.
- *The Terminal window doesn't minimize itself on launch (Mac)*: `ArcSim.command` tries to minimize its own Terminal window automatically once it's confirmed Java is present. If macOS prompts for permission for Terminal to control Terminal, allow it (System Settings > Privacy & Security > Automation); otherwise it's harmless to ignore, and you can just minimize the window yourself — it doesn't affect the app.
- *Anything else*: the log panel at the bottom of the window is the first place to look. Errors should print there in plain language, not just a raw stack trace.
