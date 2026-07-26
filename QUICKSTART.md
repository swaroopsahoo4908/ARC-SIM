# Arc-Sim Quickstart Guide

This is a plain-language walkthrough for getting Arc-Sim running and using it for the first time.
For technical details on how each engine works internally, see `README.md`.

## 1. Requirements

- A Java 17 (or newer) runtime. If you don't already have one, download the free Eclipse Temurin
  build for your OS from [adoptium.net](https://adoptium.net) and install it before continuing.

## 2. Installing

You should have received a folder containing at least these files:

| File | Purpose |
|---|---|
| `ArcSim.jar` | The application itself -- everything is bundled inside, nothing else to install |
| `ArcSim.command` | Double-click launcher for macOS |
| `ArcSim.bat` | Double-click launcher for Windows |
| `sweep_grid.properties` | Default configuration for Engine 1 (Full Factorial Sweep) |
| `QUICKSTART.md` | This guide |
| `README.md` | Technical/engineering documentation |

Copy the whole folder anywhere convenient (your Desktop, Documents, an external drive, it doesn't
matter, nothing is hardcoded to one location). Keep all the files above together in the same
folder; the launchers expect `ArcSim.jar` to sit right next to them.
Arc-Sim is fully self-contained: it only ever reads and writes files inside this folder (your
`.ork` files, run output, and its own settings file, a hidden `.arc-sim-config.properties` written
next to `ArcSim.jar`). Nothing is installed or written anywhere else on your computer, so you can
move, copy, rename, or re-zip the whole folder freely -- it'll keep working wherever it ends up.

*macOS*: double-click `ArcSim.command`. The first time, macOS may refuse to run it (files
downloaded or copied from elsewhere are untrusted by default); if so, right-click
`ArcSim.command` and choose *Open* once, then confirm. After that, double-clicking works normally.

*Windows*: double-click `ArcSim.bat`.

If either launcher reports that Java wasn't found or is too old, install/update Java from
[adoptium.net](https://adoptium.net) (get the "JRE" or "JDK" build for your OS, version 17 or
newer) and try again.

## 3. First-run setup

The first time Arc-Sim starts, a one-time setup window appears with a single optional field:

- *Weather API key (optional)*: only needed if you want to use Engine 4 (Weather-Driven Design) or
  the "Use Current Location" button, both of which pull live weather data. Get a free key at
  [weatherapi.com](https://www.weatherapi.com) if you want this. You can safely skip it and add it
  later (see Section 6, Settings).

Click *Get Started*. This only happens once.

Every file dialog in the app (open `.ork`, grid config, output folders, Data Viewer) starts in the
same folder as `ArcSim.jar`/`ArcSim.command` each time you launch it, then follows you to wherever
you browse next for the rest of that session -- there's no separate working-folder setting to
configure or get out of sync with where you actually keep the app.

## 4. The four engines.

Arc-Sim opens with one tab per engine, plus a Data Viewer tab. Each engine tab has its own form,
a *Run* button, a progress bar, and a live log at the bottom of the window.

1. *Engine 1 -- Full Factorial Sweep*: point it at a `.ork` rocket file and exhaustively tests
   every combination of wind, temperature, pressure, launch angle, and launch site in a grid,
   producing a full results table (`.parquet` + a `.csv` summary). Each swept parameter has its
   own row in the tab: *range1* to *range2* at *step count*, editable right in the GUI -- no need
   to hand-edit a config file. Sites use the same picker as Engines 2/4 (check any combination of
   the two presets and/or Custom lat/lon/alt, with a "Use Current Location" button) -- every
   checked site is swept as its own axis. The safety cap and thread count still live in
   `sweep_grid.properties` (click "Edit sites/safety cap..."); "Load ranges from file" / "Save
   ranges to file" sync the on-screen ranges and sites with that file if you want to reuse a grid
   later. Use the *Preview combination count & time estimate* button first -- it tells you how
   long a run will take before you commit to it.
2. *Engine 2 -- Design Solver*: give it a target apogee and flight-time window plus one fixed set
   of conditions, and it solves for ballast weight, fin height, and parachute spill-hole size to
   hit those targets, saving a new `.ork` file with the solved design.
3. *Engine 3 -- Geometry Export*: exports a 3D-printable/CAD-importable mesh (STL/OBJ) of your
   rocket's outer shape, for a quick physical mockup or CAD sanity check.
4. *Engine 4 -- Weather-Driven Design*: pulls live current weather for a launch site and runs an
   end-to-end pipeline (solve, export, sensitivity check, spare fin sets) against it in one click.
   Requires the weather API key from setup.

Every run writes its output into a clearly named subfolder/file (an auto-generated timestamp is
always in the filename), so re-running never overwrites a previous result.

## 5. A typical first session

1. Open the *Engine 1* tab.
2. Browse to your `.ork` rocket file.
3. Adjust any of the seven range rows (wind avg, wind std dev, turbulence, wind direction, temp,
   pressure, rod angle) if the defaults don't fit your case -- each is *min* to *max* at *step
   count*.
4. Click *Preview combination count & time estimate* and read the printed estimate in the log.
5. If the estimate is longer than you want to wait, lower a step count or narrow a range, then
   preview again.
6. Click *Run Full Factorial Sweep*. Watch progress in the log and the live leaderboard of
   best-matching conditions found so far.
7. When it finishes, open the *Data Viewer* tab to browse the results, or open the output
   `.parquet`/`.csv` files directly in any Parquet-aware tool (pandas, DuckDB, etc.) or a text
   editor for the CSV summary.

## 6. Settings

Want to add or change the weather API key later? Use the *File > Preferences* menu at any time --
no restart needed.

*Help > About Arc-Sim* shows the installed version. *Help > Open Quickstart Guide* reopens this
document.

## 7. Troubleshooting

- *Launcher does nothing / "unidentified developer" warning (Mac)*: right-click `ArcSim.command`
  and choose *Open* once to approve it, per Section 2.
- *"No Java runtime was found"*: install Java 17+ from [adoptium.net](https://adoptium.net).
- *A run refuses to start, citing a "safety cap"*: your grid config would produce more
  combinations than the configured safety limit. Either coarsen the grid (see Section 5, step 5),
  raise `maxCombosSafety` in the config file, or check *Force* in the Engine 1 tab if you're sure
  you want to proceed anyway.
- *Weather features are greyed out or fail*: add a weather API key via *File > Preferences* (see
  Section 3).
- *The Terminal window doesn't minimize itself on launch (Mac)*: `ArcSim.command` tries to minimize
  its own Terminal window automatically once it's confirmed Java is present. If macOS prompts for
  permission for Terminal to control Terminal, allow it (System Settings > Privacy & Security >
  Automation); otherwise this is harmless to ignore; you can just minimize the window yourself,
  it doesn't affect the app.
- *Anything else*: the log panel at the bottom of the window is the first place to look; errors
  should be printed there in plain language, not just a stack trace.
