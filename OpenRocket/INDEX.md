# OpenRocket Design Files — CSWARCMOD Series

*`.ork` design iterations for the CSWARC'27 (2026-27 ARC) competition entry.*

---

## Active files (this folder)

| File | Notes |
|---|---|
| `CSWARCMOD1A.ork` | Initial 2026-27 design baseline |
| `CSWARCMOD1B.ork` | Iteration 1B |
| `CSWARCMOD1C.ork` | Iteration 1C |
| `CSWARCMOD1D.ork` | *Current canonical design* — 128 g ballast, 18 in. chute, apogee ≈ 243 m |
| `design_solved_engine2.ork` | Output from `arc-sim` Engine 3 (solver-generated, may be superseded) |

## arc-sim solver outputs (in `./OpenRocket Solves/`)

The `arc-sim` tool (Engine 3 and Engine 4) writes its solved `.ork` files into an `OpenRocket
Solves` folder created right here next to the solver inputs — i.e. `./OpenRocket Solves/`, a
sibling of this INDEX.md, not the top-level `OpenRocket/` folder directly. This is the tool's
default for any `.ork` file run from this folder; typing an explicit output path in the GUI
overrides it. Files with the `_solved_YYYYMMDD_HHMMSS` suffix are timestamped solver outputs —
the most recent is the current best solution:

| File | Notes |
|---|---|
| `CSWARCMOD1D_solved_20260715_104152.ork` | Solver run |
| `CSWARCMOD1D_solved_20260715_104232.ork` | Solver run |
| `CSWARCMOD1D_solved_20260715_110620.ork` | Solver run |
| `CSWARCMOD1D_solved_20260715_110821.ork` | *Latest* — most current solved design |

> Always open the latest `_solved_*` file in OpenRocket 23.09+ to verify the stability margin
> (calibers) is still in a safe range (1-2 cal) after any solver run. The solver trims fin sweep
> as a last-resort lever, which can move CP.

---

*See `../arc-sim/README.md` for how to re-run the solver.*
