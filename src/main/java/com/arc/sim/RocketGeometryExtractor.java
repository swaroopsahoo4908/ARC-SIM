package com.arc.sim;

import info.openrocket.core.rocketcomponent.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a simplified 2D side-profile schematic from a rocket component tree: nose cone, body
 * tubes, transitions, and fin sets, stacked nose-to-tail. Intended for rapid visual verification
 * rather than to-scale/exact CAD output: components within a stage are assumed to be stacked
 * back-to-back with no gaps or overlaps (accurate for most simple rockets; approximate for
 * designs with unusual axial offsets), and parallel staging (side-by-side boosters/pods) is not
 * rendered, only serial stacking (nose to tail).
 *
 * Each component is read defensively (per-component try/catch) so that an unexpected component
 * type or a missing accessor is skipped rather than causing the entire extraction to fail.
 */
public class RocketGeometryExtractor {

    // Number of axial stations (segment count = this value minus 1) sampled along each nose
    // cone / body tube / transition via SymmetricComponent.getRadius(x), OpenRocket's radius
    // profile function for the configured shape (conical, ogive, ellipsoid, power series,
    // parabolic, Haack/Von Karman). Direct sampling, rather than linear interpolation between
    // fore/aft radius, is required to render curved nose cone/transition shapes as curves
    // instead of a straight-sided cone approximation.
    private static final int PROFILE_SAMPLES = 32;

    public enum BodyKind { NOSE_CONE, BODY_TUBE, TRANSITION }

    public static class BodyShape {
        public BodyKind kind;
        public double xStart, length, foreRadius, aftRadius;
        /** Radius at PROFILE_SAMPLES+1 evenly-spaced axial stations from x=0 (fore) to x=length (aft):
         *  the true shape profile (curved for ogive/ellipsoid/etc., straight for conical/tube),
         *  sampled directly from OpenRocket's SymmetricComponent.getRadius(x). foreRadius/aftRadius
         *  above are profileR[0]/profileR[last], retained for callers requiring only the endpoints. */
        public double[] profileR;
        public String label;
    }

    public static class FinShape {
        public double xStart; // Axial position at which the fin root chord begins.
        public double rootChord, tipChord, sweep, height;
        public double parentRadius; // Radius of the tube the fin set is mounted on.
        public int finCount = 4; // Defaults to 4 if the component does not report a fin count.
        public double baseRotationRad = 0; // Radial angle of the first fin, in radians.
        public String label;
    }

    public static class Geometry {
        public List<BodyShape> bodies = new ArrayList<>();
        public List<FinShape> fins = new ArrayList<>();
        public double totalLength;
        public double maxRadius;
        public List<String> skipped = new ArrayList<>(); // Components that could not be rendered, recorded for diagnostic purposes.
    }

    public static Geometry extract(Rocket rocket) {
        Geometry g = new Geometry();
        double x = 0;
        for (RocketComponent stage : rocket.getChildren()) {
            x = walkStage(stage, x, g);
        }
        g.totalLength = x;
        return g;
    }

    private static double walkStage(RocketComponent stage, double xStart, Geometry g) {
        double x = xStart;
        for (RocketComponent c : stage.getChildren()) {
            try {
                if (c instanceof NoseCone) {
                    NoseCone nc = (NoseCone) c;
                    double len = nc.getLength();
                    addBody(g, BodyKind.NOSE_CONE, x, len, sampleProfile(nc, len), safeName(c));
                    x += len;
                } else if (c instanceof BodyTube) {
                    BodyTube bt = (BodyTube) c;
                    double len = bt.getLength();
                    double r = bt.getOuterRadius();
                    addBody(g, BodyKind.BODY_TUBE, x, len, sampleProfile(bt, len), safeName(c));
                    extractFins(bt, x, len, r, g);
                    x += len;
                } else if (c instanceof Transition) {
                    Transition t = (Transition) c;
                    double len = t.getLength();
                    addBody(g, BodyKind.TRANSITION, x, len, sampleProfile(t, len), safeName(c));
                    x += len;
                } else {
                    // Internal components (mass, parachute, inner tube, bulkhead, etc.) are not
                    // externally visible and are intentionally excluded from rendering.
                }
            } catch (Throwable t) {
                g.skipped.add(safeName(c) + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        return x;
    }

    private static void extractFins(BodyTube bt, double tubeXStart, double tubeLength, double tubeRadius, Geometry g) {
        for (RocketComponent child : bt.getChildren()) {
            if (child instanceof TrapezoidFinSet) {
                try {
                    TrapezoidFinSet f = (TrapezoidFinSet) child;
                    FinShape fs = new FinShape();
                    fs.rootChord = f.getRootChord();
                    fs.tipChord = f.getTipChord();
                    fs.sweep = f.getSweep();
                    fs.height = f.getHeight();
                    fs.parentRadius = tubeRadius;
                    try {
                        fs.finCount = Math.max(1, f.getFinCount());
                        fs.baseRotationRad = f.getBaseRotation();
                    } catch (Throwable ignored) {
                        // Retain the defaults (4 fins, 0 rad) assigned above.
                    }
                    fs.label = safeName(child);
                    // Approximation: fins are assumed to sit at the aft end of their tube.
                    fs.xStart = tubeXStart + tubeLength - fs.rootChord;
                    g.fins.add(fs);
                } catch (Throwable t) {
                    g.skipped.add(safeName(child) + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        }
    }

    /**
     * Samples a component's radius profile via SymmetricComponent.getRadius(x), the same
     * function used by OpenRocket's 3D renderer and CG/CD calculations, at PROFILE_SAMPLES+1
     * evenly-spaced axial stations from x=0 to x=length. Direct sampling is required to render
     * ellipsoid, ogive, power-series, parabolic, and Haack nose cones and transitions as curved
     * shapes rather than a straight-sided cone approximation, which is exact only for
     * Shape.CONICAL.
     */
    private static double[] sampleProfile(SymmetricComponent c, double length) {
        double[] r = new double[PROFILE_SAMPLES + 1];
        for (int i = 0; i <= PROFILE_SAMPLES; i++) {
            double x = length * i / (double) PROFILE_SAMPLES;
            r[i] = c.getRadius(x);
        }
        return r;
    }

    private static void addBody(Geometry g, BodyKind kind, double xStart, double length,
                                 double[] profileR, String label) {
        BodyShape s = new BodyShape();
        s.kind = kind;
        s.xStart = xStart;
        s.length = length;
        s.profileR = profileR;
        s.foreRadius = profileR[0];
        s.aftRadius = profileR[profileR.length - 1];
        s.label = label;
        g.bodies.add(s);
        double maxR = 0;
        for (double v : profileR) maxR = Math.max(maxR, v);
        g.maxRadius = Math.max(g.maxRadius, maxR);
    }

    private static String safeName(RocketComponent c) {
        try {
            String n = c.getName();
            return (n == null || n.isBlank()) ? c.getClass().getSimpleName() : n;
        } catch (Exception e) {
            return c.getClass().getSimpleName();
        }
    }
}
