package com.arc.sim;

import info.openrocket.core.rocketcomponent.*;

import java.util.ArrayList;
import java.util.List;

public class RocketGeometryExtractor {

    private static final int PROFILE_SAMPLES = 32;

    public enum BodyKind { NOSE_CONE, BODY_TUBE, TRANSITION }

    public static class BodyShape {
        public BodyKind kind;
        public double xStart, length, foreRadius, aftRadius;

        public double[] profileR;
        public String label;
    }

    public static class FinShape {
        public double xStart;
        public double rootChord, tipChord, sweep, height;
        public double parentRadius;
        public int finCount = 4;
        public double baseRotationRad = 0;
        public String label;
    }

    public enum AppendageKind { RAIL_BUTTON, LAUNCH_LUG }

    public static class AppendageShape {
        public AppendageKind kind;
        public double xCenter;
        public double parentRadius;
        public double protrusionHeight;
        public double lengthAlongAxis;
        public String label;
    }

    public static class PointMassShape {
        public double xCenter;
        public double lengthAlongAxis;
        public double massKg;
        public String kindLabel;
        public String label;
    }

    public static class Geometry {
        public List<BodyShape> bodies = new ArrayList<>();
        public List<FinShape> fins = new ArrayList<>();
        public List<AppendageShape> appendages = new ArrayList<>();
        public List<PointMassShape> pointMasses = new ArrayList<>();
        public double totalLength;
        public double maxRadius;
        public List<String> skipped = new ArrayList<>();
    }

    public static Geometry extract(Rocket rocket) {
        Geometry g = new Geometry();
        double x = 0;
        for (RocketComponent stage : rocket.getChildren()) {
            x = walkStage(stage, x, g);
        }
        g.totalLength = x;
        for (RocketComponent stage : rocket.getChildren()) {
            collectAppendagesAndMasses(stage, g);
        }
        return g;
    }

    private static void collectAppendagesAndMasses(RocketComponent comp, Geometry g) {
        try {
            if (comp instanceof RailButton rb) {
                double xCenter = firstLocationX(rb);
                AppendageShape a = new AppendageShape();
                a.kind = AppendageKind.RAIL_BUTTON;
                a.xCenter = xCenter;
                a.parentRadius = radiusNear(g, xCenter);
                a.protrusionHeight = rb.getTotalHeight();
                a.lengthAlongAxis = Math.max(0.003, rb.getOuterDiameter());
                a.label = safeName(rb);
                g.appendages.add(a);
            } else if (comp instanceof LaunchLug lug) {
                double xCenter = firstLocationX(lug);
                AppendageShape a = new AppendageShape();
                a.kind = AppendageKind.LAUNCH_LUG;
                a.xCenter = xCenter;
                a.parentRadius = radiusNear(g, xCenter);
                a.protrusionHeight = Math.max(0.003, lug.getOuterRadius() * 2.0);
                a.lengthAlongAxis = lug.getLength();
                a.label = safeName(lug);
                g.appendages.add(a);
            } else if (comp instanceof MassComponent || comp instanceof Parachute
                    || comp instanceof Streamer || comp instanceof ShockCord) {
                double xCenter = firstLocationX(comp);
                PointMassShape m = new PointMassShape();
                m.xCenter = xCenter;
                m.lengthAlongAxis = Math.max(0.01, comp.getLength());
                m.massKg = safeMass(comp);
                m.kindLabel = comp.getClass().getSimpleName();
                m.label = safeName(comp);
                g.pointMasses.add(m);
            }
        } catch (Throwable t) {
            g.skipped.add(safeName(comp) + " (" + t.getClass().getSimpleName() + ")");
        }
        for (RocketComponent child : comp.getChildren()) {
            collectAppendagesAndMasses(child, g);
        }
    }

    private static double firstLocationX(RocketComponent c) {
        info.openrocket.core.util.Coordinate[] locs = c.getComponentLocations();
        return (locs != null && locs.length > 0) ? locs[0].x : 0.0;
    }

    private static double safeMass(RocketComponent c) {
        try {
            return c.getComponentMass();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static double radiusNear(Geometry g, double x) {
        for (BodyShape b : g.bodies) {
            if (x >= b.xStart - 1e-6 && x <= b.xStart + b.length + 1e-6) {
                double frac = b.length > 1e-9 ? Math.min(1.0, Math.max(0.0, (x - b.xStart) / b.length)) : 0.0;
                int idx = (int) Math.round(frac * (b.profileR.length - 1));
                return b.profileR[idx];
            }
        }
        return g.maxRadius > 0 ? g.maxRadius : 0.03;
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
                    }
                    fs.label = safeName(child);
                    fs.xStart = tubeXStart + tubeLength - fs.rootChord;
                    g.fins.add(fs);
                } catch (Throwable t) {
                    g.skipped.add(safeName(child) + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        }
    }

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

