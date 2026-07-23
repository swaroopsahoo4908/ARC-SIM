package com.arc.sim;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Engine 3: MeshExporter. Converts extracted rocket geometry (.ork-derived) to STL/OBJ mesh
 * files.
 *
 * Converts RocketGeometryExtractor's simplified 2D side-profile (nose cone / body tube /
 * transition radii by station, plus trapezoidal fin planforms) into a triangle mesh, revolved
 * about the rocket's X axis, and writes ASCII STL and/or Wavefront OBJ output.
 *
 * Fidelity constraints, approximate geometry only, not CAD-accurate:
 *   - Body-of-revolution surfaces only (nose cone / body tube / transition); no wall thickness,
 *     no internal components, no fillets, no surface texture.
 *   - Fins are extruded flat trapezoidal panels (root chord / tip chord / sweep / height) of
 *     small constant thickness, spaced evenly by fin count and base rotation. Airfoil cross-
 *     section profiles are not modeled.
 *   - Multi-body stacking uses the same no-gaps/no-overlaps serial-stacking assumption as
 *     RocketPreviewPanel; side-by-side staging (boosters/pods) is not rendered.
 * Suitable for rapid 3D-print or CAD-import verification of the outer mold line; not a
 * substitute for a dimensionally precise CAD model.
 *
 * Units: OpenRocket's internal units are SI (meters). This exporter scales all output by
 * MM_PER_M so written STL/OBJ files are in millimeters, the standard convention for
 * STL-consuming CAD and slicer tools.
 */
public class MeshExporter {

    private static final double MM_PER_M = 1000.0;
    private static final int REVOLUTION_SEGMENTS = 32; // Circumferential resolution of the body-of-revolution mesh.
    private static final double FIN_THICKNESS_M = 0.003; // 3 mm; nominal thickness for solid-closure only, not structural.

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

    /** Builds the full triangle mesh (bodies + fins) from an extracted rocket geometry, in millimeters. */
    public static List<Triangle> buildMesh(RocketGeometryExtractor.Geometry g) {
        List<Triangle> tris = new ArrayList<>();
        for (RocketGeometryExtractor.BodyShape body : g.bodies) {
            addBodyOfRevolution(tris, body);
        }
        if (!g.bodies.isEmpty()) {
            RocketGeometryExtractor.BodyShape last = g.bodies.get(g.bodies.size() - 1);
            addEndCap(tris, last.xStart + last.length, last.aftRadius, false); // Aft cap, facing +X.
        }
        for (RocketGeometryExtractor.FinShape fin : g.fins) {
            addFinSet(tris, fin);
        }
        return tris;
    }

    /**
     * Builds a mesh of the fin set(s) only, excluding body tube/nose cone/transition surfaces.
     * Used by Engine 4 (WeatherDrivenDesign) to export standalone fin-only CAD for each
     * wind-speed margin variant; only fin height varies across variants, so the body geometry
     * (identical to the main design) does not require re-export for each margin.
     */
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

    /**
     * Revolves the body's sampled radius profile (body.profileR, sourced from OpenRocket's
     * SymmetricComponent.getRadius(x), one station per axial sample) about the X axis, producing
     * one ring of triangles per pair of adjacent stations. Sampling the true profile, rather than
     * interpolating linearly between fore and aft radius, is required to represent ellipsoid,
     * ogive, power-series, parabolic, and Haack nose cones and transitions as curved solids;
     * linear interpolation is exact only for Shape.CONICAL.
     */
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
                    // Station collapses to a point (nose tip); emit a fan triangle in place of a quad.
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

    /** Flat disc cap at a given axial station; closes off the aft end of the last body. */
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

    /**
     * Extruded flat trapezoidal panels, evenly spaced by fin count about the tube, each modeled
     * as a thin solid slab (root/tip chord by height, +/- FIN_THICKNESS_M/2 tangentially) to
     * produce a closed volume rather than a zero-thickness sheet.
     */
    private static void addFinSet(List<Triangle> tris, RocketGeometryExtractor.FinShape fin) {
        double halfT = FIN_THICKNESS_M / 2.0;
        double dTheta = 2 * Math.PI / fin.finCount;

        // Planform corners in the fin's local (axial x, radial r) plane.
        double rootLeadX = fin.xStart, rootTrailX = fin.xStart + fin.rootChord;
        double tipLeadX = fin.xStart + fin.sweep, tipTrailX = fin.xStart + fin.sweep + fin.tipChord;
        double rBase = fin.parentRadius, rTip = fin.parentRadius + fin.height;

        for (int i = 0; i < fin.finCount; i++) {
            double theta = fin.baseRotationRad + i * dTheta;
            // Radial unit vector (in the fin mounting plane) and tangential unit vector (thickness direction).
            double radY = Math.cos(theta), radZ = Math.sin(theta);
            double tanY = -Math.sin(theta), tanZ = Math.cos(theta);

            double[] rl = {rootLeadX, rBase * radY, rBase * radZ};
            double[] rt = {rootTrailX, rBase * radY, rBase * radZ};
            double[] tt = {tipTrailX, rTip * radY, rTip * radZ};
            double[] tl = {tipLeadX, rTip * radY, rTip * radZ};

            double[][] plusFace = offsetQuad(rl, rt, tt, tl, tanY, tanZ, halfT);
            double[][] minusFace = offsetQuad(rl, rt, tt, tl, tanY, tanZ, -halfT);

            addQuad(tris, plusFace[0], plusFace[1], plusFace[2], plusFace[3]); // +thickness face.
            addQuad(tris, minusFace[3], minusFace[2], minusFace[1], minusFace[0]); // -thickness face, reversed winding.

            // Side walls closing the slab: leading edge, trailing edge, tip, root.
            addQuad(tris, minusFace[0], minusFace[3], plusFace[3], plusFace[0]); // Leading edge (root-lead to tip-lead).
            addQuad(tris, plusFace[1], plusFace[2], minusFace[2], minusFace[1]); // Trailing edge.
            addQuad(tris, minusFace[3], minusFace[2], plusFace[2], plusFace[3]); // Tip.
            addQuad(tris, plusFace[0], plusFace[1], minusFace[1], minusFace[0]); // Root.
        }
    }

    private static double[][] offsetQuad(double[] a, double[] b, double[] c, double[] d,
                                          double tanY, double tanZ, double t) {
        return new double[][]{
                offset(a, tanY, tanZ, t), offset(b, tanY, tanZ, t), offset(c, tanY, tanZ, t), offset(d, tanY, tanZ, t)
        };
    }

    private static double[] offset(double[] p, double tanY, double tanZ, double t) {
        // p is expressed in meters here (conversion to mm occurs at addQuad); the thickness
        // offset is applied in meters, then scaled.
        return new double[]{p[0] * MM_PER_M, (p[1] + tanY * t) * MM_PER_M, (p[2] + tanZ * t) * MM_PER_M};
    }

    private static void addQuad(List<Triangle> tris, double[] a, double[] b, double[] c, double[] d) {
        tris.add(new Triangle(a, b, c));
        tris.add(new Triangle(a, c, d));
    }

    /** Writes an ASCII STL file (millimeters). */
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

    /** Writes a Wavefront OBJ file (millimeters). Vertices are not deduplicated, trading output size for implementation simplicity. */
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
