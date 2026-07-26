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

    public static class Geometry {
        public List<BodyShape> bodies = new ArrayList<>();
        public List<FinShape> fins = new ArrayList<>();
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

