package com.arc.sim;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MeshExporter {

    private static final double MM_PER_M = 1000.0;
    private static final int REVOLUTION_SEGMENTS = 32;
    private static final double FIN_THICKNESS_M = 0.003;

    public static class Triangle {
        public final double[] n = new double[3];
        public final double[][] v = new double[3][3];

        Triangle(double[] a, double[] b, double[] c) {
            v[0] = a; v[1] = b; v[2] = c;
            computeNormal();
        }

        private void computeNormal() {
            double ux = v[1][0] - v[0][0], uy = v[1][1] - v[0][1], uz = v[1][2] - v[0][2];
            double wx = v[2][0] - v[0][0], wy = v[2][1] - v[0][1], wz = v[2][2] - v[0][2];
            double nx = uy * wz - uz * wy;
            double ny = uz * wx - ux * wz;
            double nz = ux * wy - uy * wx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12) { n[0] = 0; n[1] = 0; n[2] = 1; return; }
            n[0] = nx / len; n[1] = ny / len; n[2] = nz / len;
        }
    }

    public static List<Triangle> buildMesh(RocketGeometryExtractor.Geometry g) {
        List<Triangle> tris = new ArrayList<>();
        for (RocketGeometryExtractor.BodyShape body : g.bodies) {
            addBodyOfRevolution(tris, body);
        }
        if (!g.bodies.isEmpty()) {
            RocketGeometryExtractor.BodyShape last = g.bodies.get(g.bodies.size() - 1);
            addEndCap(tris, last.xStart + last.length, last.aftRadius, false);
        }
        for (RocketGeometryExtractor.FinShape fin : g.fins) {
            addFinSet(tris, fin);
        }
        return tris;
    }

    public static List<Triangle> buildFinSetMesh(List<RocketGeometryExtractor.FinShape> fins) {
        List<Triangle> tris = new ArrayList<>();
        for (RocketGeometryExtractor.FinShape fin : fins) {
            addFinSet(tris, fin);
        }
        return tris;
    }

    private static double[] cyl(double x, double r, double theta) {
        return new double[]{x * MM_PER_M, r * Math.cos(theta) * MM_PER_M, r * Math.sin(theta) * MM_PER_M};
    }

    private static void addBodyOfRevolution(List<Triangle> tris, RocketGeometryExtractor.BodyShape body) {
        double[] profile = body.profileR;
        int stations = profile.length;
        double dTheta = 2 * Math.PI / REVOLUTION_SEGMENTS;
        double dx = body.length / (double) (stations - 1);

        for (int s = 0; s < stations - 1; s++) {
            double x0 = body.xStart + s * dx, x1 = body.xStart + (s + 1) * dx;
            double r0 = profile[s], r1 = profile[s + 1];

            for (int i = 0; i < REVOLUTION_SEGMENTS; i++) {
                double t0 = i * dTheta, t1 = (i + 1) * dTheta;

                if (r0 < 1e-9) {

                    double[] apex = cyl(x0, 0, 0);
                    double[] b0 = cyl(x1, r1, t0);
                    double[] b1 = cyl(x1, r1, t1);
                    tris.add(new Triangle(apex, b0, b1));
                } else if (r1 < 1e-9) {
                    double[] apex = cyl(x1, 0, 0);
                    double[] a0 = cyl(x0, r0, t0);
                    double[] a1 = cyl(x0, r0, t1);
                    tris.add(new Triangle(a0, a1, apex));
                } else {
                    double[] a0 = cyl(x0, r0, t0);
                    double[] a1 = cyl(x0, r0, t1);
                    double[] b0 = cyl(x1, r1, t0);
                    double[] b1 = cyl(x1, r1, t1);
                    tris.add(new Triangle(a0, a1, b1));
                    tris.add(new Triangle(a0, b1, b0));
                }
            }
        }
    }

    private static void addEndCap(List<Triangle> tris, double x, double radius, boolean facingNegativeX) {
        if (radius < 1e-9) return;
        double dTheta = 2 * Math.PI / REVOLUTION_SEGMENTS;
        double[] center = new double[]{x * MM_PER_M, 0, 0};
        for (int i = 0; i < REVOLUTION_SEGMENTS; i++) {
            double t0 = i * dTheta, t1 = (i + 1) * dTheta;
            double[] p0 = cyl(x, radius, t0);
            double[] p1 = cyl(x, radius, t1);
            if (facingNegativeX) {
                tris.add(new Triangle(center, p1, p0));
            } else {
                tris.add(new Triangle(center, p0, p1));
            }
        }
    }

    private static void addFinSet(List<Triangle> tris, RocketGeometryExtractor.FinShape fin) {
        double halfT = FIN_THICKNESS_M / 2.0;
        double dTheta = 2 * Math.PI / fin.finCount;

        double rootLeadX = fin.xStart, rootTrailX = fin.xStart + fin.rootChord;
        double tipLeadX = fin.xStart + fin.sweep, tipTrailX = fin.xStart + fin.sweep + fin.tipChord;
        double rBase = fin.parentRadius, rTip = fin.parentRadius + fin.height;

        for (int i = 0; i < fin.finCount; i++) {
            double theta = fin.baseRotationRad + i * dTheta;

            double radY = Math.cos(theta), radZ = Math.sin(theta);
            double tanY = -Math.sin(theta), tanZ = Math.cos(theta);

            double[] rl = {rootLeadX, rBase * radY, rBase * radZ};
            double[] rt = {rootTrailX, rBase * radY, rBase * radZ};
            double[] tt = {tipTrailX, rTip * radY, rTip * radZ};
            double[] tl = {tipLeadX, rTip * radY, rTip * radZ};

            double[][] plusFace = offsetQuad(rl, rt, tt, tl, tanY, tanZ, halfT);
            double[][] minusFace = offsetQuad(rl, rt, tt, tl, tanY, tanZ, -halfT);

            addQuad(tris, plusFace[0], plusFace[1], plusFace[2], plusFace[3]);
            addQuad(tris, minusFace[3], minusFace[2], minusFace[1], minusFace[0]);

            addQuad(tris, minusFace[0], minusFace[3], plusFace[3], plusFace[0]);
            addQuad(tris, plusFace[1], plusFace[2], minusFace[2], minusFace[1]);
            addQuad(tris, minusFace[3], minusFace[2], plusFace[2], plusFace[3]);
            addQuad(tris, plusFace[0], plusFace[1], minusFace[1], minusFace[0]);
        }
    }

    private static double[][] offsetQuad(double[] a, double[] b, double[] c, double[] d,
                                          double tanY, double tanZ, double t) {
        return new double[][]{
                offset(a, tanY, tanZ, t), offset(b, tanY, tanZ, t), offset(c, tanY, tanZ, t), offset(d, tanY, tanZ, t)
        };
    }

    private static double[] offset(double[] p, double tanY, double tanZ, double t) {

        return new double[]{p[0] * MM_PER_M, (p[1] + tanY * t) * MM_PER_M, (p[2] + tanZ * t) * MM_PER_M};
    }

    private static void addQuad(List<Triangle> tris, double[] a, double[] b, double[] c, double[] d) {
        tris.add(new Triangle(a, b, c));
        tris.add(new Triangle(a, c, d));
    }

    public static void writeStl(List<Triangle> tris, File out, String solidName) throws IOException {
        try (PrintWriter pw = new PrintWriter(out, StandardCharsets.UTF_8)) {
            pw.println("solid " + sanitize(solidName));
            for (Triangle t : tris) {
                pw.printf("facet normal %s %s %s%n", f(t.n[0]), f(t.n[1]), f(t.n[2]));
                pw.println("  outer loop");
                for (double[] v : t.v) {
                    pw.printf("    vertex %s %s %s%n", f(v[0]), f(v[1]), f(v[2]));
                }
                pw.println("  endloop");
                pw.println("endfacet");
            }
            pw.println("endsolid " + sanitize(solidName));
        }
    }

    public static void writeObj(List<Triangle> tris, File out, String objectName) throws IOException {
        try (PrintWriter pw = new PrintWriter(out, StandardCharsets.UTF_8)) {
            pw.println("# Exported by ARC Rocket Simulation Toolkit -- Engine 3: Geometry Export");
            pw.println("# Basic body-of-revolution + flat-fin approximation, units millimeters");
            pw.println("o " + sanitize(objectName));
            int vIdx = 1;
            for (Triangle t : tris) {
                for (double[] v : t.v) {
                    pw.printf("v %s %s %s%n", f(v[0]), f(v[1]), f(v[2]));
                }
                pw.printf("f %d %d %d%n", vIdx, vIdx + 1, vIdx + 2);
                vIdx += 3;
            }
        }
    }

    private static String f(double d) {
        return String.format("%.5f", d);
    }

    private static String sanitize(String s) {
        return s == null ? "rocket" : s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}

