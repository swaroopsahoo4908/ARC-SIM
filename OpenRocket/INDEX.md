# OpenRocket Design Files — CSWARCMOD Series

*`.ork` design iterations for the CSWARC'27 (2026-27 ARC) competition entry, plus this folder's
`arc-sim` output subfolders.*

---

## Active files (this folder)

| File | Notes |
|---|---|
| `CSWARCMOD1A.ork` | Initial 2026-27 design baseline |
| `CSWARCMOD1B.ork` | Iteration 1B |
| `CSWARCMOD1C.ork` | Iteration 1C |
| `CSWARCMOD1D.ork` | Iteration 1D |
| `CSWARCMOD1E.ork` | *Current canonical design* — target apogee ≈ 243.84 m |

## arc-sim output subfolders

`arc-sim`'s engines and tools each write into their own subfolder here, next to the `.ork` inputs,
using `AppConfig`'s working-folder default. Every run is auto-named with a timestamp, so nothing
here ever gets overwritten by a later run.

| Subfolder | Written by | Contents |
|---|---|---|
| `Full Factorial/` | Engine 1 (Full Factorial Sweep) | `<rocket>_fullfactorial_<timestamp>.parquet` + companion `_summary.csv` |
| `OpenRocket Solves/` | Engine 2 (Design Solver), run standalone | `<rocket>_solved_<timestamp>.ork` |
| `CAD Files/` | Geometry Exporter | One `<rocket>_geometry_<timestamp>/` folder per run, each with `.stl` + `.obj` |
| `Engine 4/` | Engine 4 (Weather-Driven Design) | One `<rocket>_weatherdesign_<timestamp>/` folder per run, each with its own solved `.ork`, main CAD `.stl`/`.obj`, a local-conditions `.xlsx`, and 4 margin fin-set `.stl`/`.obj` pairs |
| `Rocket Builder/` | Rocket Builder | New from-scratch `.ork` designs, saved wherever/whenever you choose to "Save As" -- filenames aren't auto-timestamped since it's an editor, not a batch tool, so name them yourself (this folder is just the default starting point the first time you save a never-before-saved rocket in a session). |

Note: a Design Solver run launched directly from Engine 2's tab lands in `OpenRocket Solves/`; a
solve run as *step 1* of an Engine 4 pipeline instead lands inside that run's own
`Engine 4/<rocket>_weatherdesign_<timestamp>/` folder alongside the rest of that pipeline's output
— check both if you're hunting for a particular solved design.

### Latest standalone solve (`OpenRocket Solves/`)

`CSWARCMOD1E_solved_20260720_220917.ork` is the most recent standalone Engine 2 run. Files with the
`_solved_YYYYMMDD_HHMMSS` suffix are timestamped solver outputs; sort by timestamp for the current
best.

### Latest Engine 4 pipeline run

`Engine 4/CSWARCMOD1E_weatherdesign_20260723_182208/` is the most recent end-to-end weather-driven
run, with its own solved `.ork`, CAD, local-conditions sweep, and margin fin sets.

> Always open the latest solved `.ork` in OpenRocket 23.09+ to verify the stability margin
> (calibers) is still in a safe range (1-2 cal) after any solver run. The solver trims fin sweep
> as a last-resort lever, which can move CP.

---

*See `../README.md` and `../QUICKSTART.md` for how to run each engine.*
