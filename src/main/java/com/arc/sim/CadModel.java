package com.arc.sim;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The ARC V2 airframe as measured from the Onshape STEP export (ARC_V2_1.zip).
 *
 * Parts are stored as gzip-compressed indexed triangle meshes (see src/main/resources/cad) already placed in the
 * rocket frame used by {@link MeshExporter}: x runs from the nose tip aft, y and z are radial, units millimetres.
 * The fin is stored once in a fin-local frame (axial, radial, thickness) and is re-conformed to whatever fin
 * planform the loaded rocket has, so Engines 2 and 3 can resize fins and still get a matching CAD mesh.
 */
public final class CadModel {

    public static final double LENGTH_MM = 735.0;

    public static final double NOSE_LEN_M = 0.152;
    public static final double UPPER_LEN_M = 0.163;
    public static final double TRANSITION_LEN_M = 0.115;
    public static final double LOWER_LEN_M = 0.305;
    public static final double UPPER_RADIUS_M = 0.027;
    public static final double LOWER_RADIUS_M = 0.033;
    public static final double FIN_ROOT_M = 0.130;
    public static final double FIN_TIP_M = 0.0552;
    public static final double FIN_SWEEP_M = 0.055;
    public static final double FIN_HEIGHT_M = 0.070;
    public static final double FIN_THICKNESS_M = 0.0024;

    private static final double LENGTH_TOL_MM = 0.75;
    private static final double RADIUS_TOL_MM = 0.4;

    /** Triangle class per face: lets the viewer colour fins and internals differently. */
    public static final byte CLASS_AIRFRAME = 0, CLASS_FIN = 1, CLASS_INTERNAL = 2;

    static final class Mesh {
        final float[] v;
        final int[] t;

        Mesh(float[] v, int[] t) {
            this.v = v;
            this.t = t;
        }

        int triCount() {
            return t.length / 3;
        }
    }

    /** Flat triangle soup: 9 floats per triangle, plus a class byte per triangle. */
    public static final class Flat {
        public final float[] xyz;
        public final byte[] cls;

        Flat(float[] xyz, byte[] cls) {
            this.xyz = xyz;
            this.cls = cls;
        }

        public int triCount() {
            return cls.length;
        }
    }

    private static CadModel instance;

    public static synchronized CadModel get() {
        if (instance == null) {
            try {
                instance = new CadModel();
            } catch (IOException e) {
                throw new IllegalStateException("Could not read the bundled ARC V2 CAD meshes: " + e.getMessage(), e);
            }
        }
        return instance;
    }

    private final Mesh nose, upper, mid, lower, motorMount, retainer, fin;
    private Flat airframeOnly, airframeWithInternals;

    private CadModel() throws IOException {
        nose = read("nose");
        upper = read("upper");
        mid = read("mid");
        lower = read("lower");
        motorMount = read("motor_mount");
        retainer = read("retainer");
        fin = read("fin");
    }

    private static Mesh read(String name) throws IOException {
        String path = "/cad/" + name + ".arcmesh";
        try (InputStream raw = CadModel.class.getResourceAsStream(path)) {
            if (raw == null) throw new IOException("missing resource " + path);
            DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw), 1 << 16));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!"ARCM".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("bad magic in " + path);
            int nv = Integer.reverseBytes(in.readInt());
            int nt = Integer.reverseBytes(in.readInt());
            float[] v = new float[nv * 3];
            for (int i = 0; i < v.length; i++) v[i] = Float.intBitsToFloat(Integer.reverseBytes(in.readInt()));
            int[] t = new int[nt * 3];
            for (int i = 0; i < t.length; i++) t[i] = Integer.reverseBytes(in.readInt());
            return new Mesh(v, t);
        }
    }

    /** Resource text of the part table (volumes, PLA masses, centres of gravity). */
    public static String partsJson() {
        try (InputStream in = CadModel.class.getResourceAsStream("/cad/arc_v2_parts.json")) {
            if (in == null) throw new IOException("missing /cad/arc_v2_parts.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Null when the loaded rocket is the ARC V2 airframe (nose, tube, transition, tube) within tolerance; otherwise a
     * short reason. Fins are free to differ, they are conformed separately.
     */
    public String mismatch(RocketGeometryExtractor.Geometry g) {
        if (g == null || g.bodies.size() != 4) {
            return "the rocket does not have the four airframe sections of ARC V2 (nose, tube, transition, tube)";
        }
        RocketGeometryExtractor.BodyKind[] kinds = {
                RocketGeometryExtractor.BodyKind.NOSE_CONE, RocketGeometryExtractor.BodyKind.BODY_TUBE,
                RocketGeometryExtractor.BodyKind.TRANSITION, RocketGeometryExtractor.BodyKind.BODY_TUBE};
        double[] len = {NOSE_LEN_M, UPPER_LEN_M, TRANSITION_LEN_M, LOWER_LEN_M};
        double[] fore = {0.0, UPPER_RADIUS_M, UPPER_RADIUS_M, LOWER_RADIUS_M};
        double[] aft = {UPPER_RADIUS_M, UPPER_RADIUS_M, LOWER_RADIUS_M, LOWER_RADIUS_M};
        String[] names = {"nose cone", "upper tube", "transition", "lower tube"};
        for (int i = 0; i < 4; i++) {
            RocketGeometryExtractor.BodyShape b = g.bodies.get(i);
            if (b.kind != kinds[i]) return names[i] + " is a different kind of section";
            if (Math.abs(b.length - len[i]) * 1000.0 > LENGTH_TOL_MM) {
                return String.format("%s is %.1f mm long (CAD: %.1f mm)", names[i], b.length * 1000.0, len[i] * 1000.0);
            }
            if (i > 0 && Math.abs(b.foreRadius - fore[i]) * 1000.0 > RADIUS_TOL_MM) {
                return String.format("%s fore radius is %.1f mm (CAD: %.1f mm)", names[i], b.foreRadius * 1000.0, fore[i] * 1000.0);
            }
            if (Math.abs(b.aftRadius - aft[i]) * 1000.0 > RADIUS_TOL_MM) {
                return String.format("%s aft radius is %.1f mm (CAD: %.1f mm)", names[i], b.aftRadius * 1000.0, aft[i] * 1000.0);
            }
        }
        return null;
    }

    /** Airframe, retainer and (optionally) the internal motor mount, plus conformed fins for every fin set. */
    public synchronized Flat build(RocketGeometryExtractor.Geometry g, boolean internals) {
        Flat base = internals ? airframeWithInternals() : airframeOnly();
        List<RocketGeometryExtractor.FinShape> fins = g == null ? new ArrayList<>() : g.fins;
        return append(base, finFlat(fins));
    }

    /** Only the fins (all fin sets), conformed to the given planforms. */
    public synchronized Flat buildFins(List<RocketGeometryExtractor.FinShape> fins) {
        return finFlat(fins);
    }

    public List<MeshExporter.Triangle> toTriangles(Flat f) {
        List<MeshExporter.Triangle> out = new ArrayList<>(f.triCount());
        for (int i = 0; i < f.triCount(); i++) {
            int o = i * 9;
            out.add(new MeshExporter.Triangle(
                    new double[]{f.xyz[o], f.xyz[o + 1], f.xyz[o + 2]},
                    new double[]{f.xyz[o + 3], f.xyz[o + 4], f.xyz[o + 5]},
                    new double[]{f.xyz[o + 6], f.xyz[o + 7], f.xyz[o + 8]}));
        }
        return out;
    }

    public List<MeshExporter.Triangle> buildMesh(RocketGeometryExtractor.Geometry g) {
        return toTriangles(build(g, true));
    }

    public List<MeshExporter.Triangle> buildFinMesh(List<RocketGeometryExtractor.FinShape> fins) {
        return toTriangles(buildFins(fins));
    }

    private Flat airframeOnly() {
        if (airframeOnly == null) {
            airframeOnly = flatten(new Mesh[]{nose, upper, mid, lower, retainer},
                    new byte[]{CLASS_AIRFRAME, CLASS_AIRFRAME, CLASS_AIRFRAME, CLASS_AIRFRAME, CLASS_INTERNAL});
        }
        return airframeOnly;
    }

    private Flat airframeWithInternals() {
        if (airframeWithInternals == null) {
            airframeWithInternals = flatten(new Mesh[]{nose, upper, mid, lower, retainer, motorMount},
                    new byte[]{CLASS_AIRFRAME, CLASS_AIRFRAME, CLASS_AIRFRAME, CLASS_AIRFRAME, CLASS_INTERNAL, CLASS_INTERNAL});
        }
        return airframeWithInternals;
    }

    private static Flat flatten(Mesh[] meshes, byte[] classes) {
        int n = 0;
        for (Mesh m : meshes) n += m.triCount();
        float[] xyz = new float[n * 9];
        byte[] cls = new byte[n];
        int tri = 0;
        for (int k = 0; k < meshes.length; k++) {
            Mesh m = meshes[k];
            for (int i = 0; i < m.triCount(); i++, tri++) {
                for (int c = 0; c < 3; c++) {
                    int vi = m.t[i * 3 + c] * 3;
                    xyz[tri * 9 + c * 3] = m.v[vi];
                    xyz[tri * 9 + c * 3 + 1] = m.v[vi + 1];
                    xyz[tri * 9 + c * 3 + 2] = m.v[vi + 2];
                }
                cls[tri] = classes[k];
            }
        }
        return new Flat(xyz, cls);
    }

    private static Flat append(Flat a, Flat b) {
        if (b.triCount() == 0) return a;
        float[] xyz = new float[a.xyz.length + b.xyz.length];
        System.arraycopy(a.xyz, 0, xyz, 0, a.xyz.length);
        System.arraycopy(b.xyz, 0, xyz, a.xyz.length, b.xyz.length);
        byte[] cls = new byte[a.cls.length + b.cls.length];
        System.arraycopy(a.cls, 0, cls, 0, a.cls.length);
        System.arraycopy(b.cls, 0, cls, a.cls.length, b.cls.length);
        return new Flat(xyz, cls);
    }

    private Flat finFlat(List<RocketGeometryExtractor.FinShape> fins) {
        int total = 0;
        for (RocketGeometryExtractor.FinShape f : fins) total += Math.max(1, f.finCount) * fin.triCount();
        float[] xyz = new float[total * 9];
        byte[] cls = new byte[total];
        int tri = 0;
        for (RocketGeometryExtractor.FinShape fs : fins) {
            int count = Math.max(1, fs.finCount);
            // Template planform, millimetres from the nose tip.
            double rb0 = LOWER_RADIUS_M * 1000.0, h0 = FIN_HEIGHT_M * 1000.0;
            double le0 = LENGTH_MM - FIN_ROOT_M * 1000.0, sw0 = FIN_SWEEP_M * 1000.0;
            double cr0 = FIN_ROOT_M * 1000.0, ct0 = FIN_TIP_M * 1000.0;
            // Requested planform.
            double rb1 = fs.parentRadius * 1000.0, h1 = fs.height * 1000.0;
            double le1 = fs.xStart * 1000.0, sw1 = fs.sweep * 1000.0;
            double cr1 = fs.rootChord * 1000.0, ct1 = fs.tipChord * 1000.0;
            double hScale = h0 > 0 ? h1 / h0 : 1.0;
            for (int k = 0; k < count; k++) {
                double theta = fs.baseRotationRad + k * 2 * Math.PI / count;
                double ct = Math.cos(theta), st = Math.sin(theta);
                for (int i = 0; i < fin.triCount(); i++, tri++) {
                    for (int c = 0; c < 3; c++) {
                        int vi = fin.t[i * 3 + c] * 3;
                        double a = fin.v[vi], rho = fin.v[vi + 1], t = fin.v[vi + 2];
                        double s = rho > rb0 ? Math.min(1.0, (rho - rb0) / h0) : 0.0;
                        double leS = le0 + sw0 * s, chord0 = cr0 + (ct0 - cr0) * s;
                        double u = chord0 > 1e-9 ? (a - leS) / chord0 : 0.0;
                        double chord1 = cr1 + (ct1 - cr1) * s;
                        double a1 = le1 + sw1 * s + u * chord1;
                        double rho1 = rho > rb0 ? rb1 + (rho - rb0) * hScale : rho + (rb1 - rb0);
                        xyz[tri * 9 + c * 3] = (float) a1;
                        xyz[tri * 9 + c * 3 + 1] = (float) (rho1 * ct - t * st);
                        xyz[tri * 9 + c * 3 + 2] = (float) (rho1 * st + t * ct);
                    }
                    cls[tri] = CLASS_FIN;
                }
            }
        }
        return new Flat(xyz, cls);
    }
}
