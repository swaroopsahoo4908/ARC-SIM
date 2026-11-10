package com.arc.sim;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.rocketcomponent.*;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import info.openrocket.core.startup.OpenRocketCore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ARC V2 template rocket: an OpenRocket file whose airframe, fins and masses come from the CAD, and whose
 * motor, parachute, shock cord, payload and ballast come from CSWARCMOD1E.
 *
 * Masses: every printed part carries a mass override equal to (CAD volume x Bambu Lab PLA Basic density), with the CG
 * override set to the CAD centroid. Non-printed items keep the MOD1E values.
 *
 * Solver structure is the one Engines 2 and 3 expect (see {@link RocketComponents}): the ballast MassComponent sits
 * inside the lowest body tube of the last stage, there is one main Parachute, and the fins are a TrapezoidFinSet.
 * No other MassComponent may be added to the lowest tube, because the solver scales every MassComponent found there.
 *
 * Run {@code CadTemplate <CSWARCMOD1E.ork> <out.ork>} to regenerate the file from the CAD part table.
 */
public final class CadTemplate {

    public static final String RESOURCE = "/cad/ARC_V2_template.ork";
    public static final String DISPLAY_NAME = "ARC V2 (CAD template)";

    /**
     * Scales every printed-part mass. 1.0 means each part is printed solid as modelled. Use a smaller value (for
     * example 0.8) if a sliced print with sparse infill weighs less; regenerate the template with the third argument
     * of {@link #main(String[])}.
     */
    private static double massScale = 1.0;
    private static PrintMaterial buildMaterial = PrintMaterial.BASIC;

    private CadTemplate() {
    }

    /** Copies the bundled template to a temp file and returns it. */
    public static File extractBundled() throws IOException {
        return extractBundled(PrintMaterial.current());
    }

    public static File extractBundled(PrintMaterial material) throws IOException {
        String res = material.templateResource();
        try (InputStream in = CadTemplate.class.getResourceAsStream(res)) {
            if (in == null) throw new IOException("bundled template " + res + " is missing");
            File tmp = File.createTempFile("arc-v2-template-" + material.id + "-", ".ork");
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    public static final class PartMass {
        public final String id, name, orComponent;
        public final double volumeMm3, massG, cgFromNoseTipMm;

        PartMass(String id, String name, String orComponent, double volumeMm3, double massG, double cg) {
            this.id = id;
            this.name = name;
            this.orComponent = orComponent;
            this.volumeMm3 = volumeMm3;
            this.massG = massG;
            this.cgFromNoseTipMm = cg;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<PartMass> partTable() {
        return partTable(PrintMaterial.BASIC);
    }

    public static List<PartMass> partTable(PrintMaterial material) {
        Object root = MiniJson.parse(CadModel.partsJson());
        double raw = 0;
        for (Object o : (List<Object>) ((Map<String, Object>) root).get("parts")) {
            raw += MiniJson.asDouble(((Map<String, Object>) o).get("massG"), 0);
        }
        // Calibrate to the weighed print: every part scales by measured / raw so the printed total matches the scale.
        double measured = material.printedMassG;
        double k = raw > 0 ? measured / raw : 1.0;
        List<PartMass> out = new ArrayList<>();
        for (Object o : (List<Object>) ((Map<String, Object>) root).get("parts")) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new PartMass((String) m.get("id"), (String) m.get("name"), (String) m.get("orComponent"),
                    MiniJson.asDouble(m.get("volumeMm3"), 0), MiniJson.asDouble(m.get("massG"), 0) * k,
                    MiniJson.asDouble(m.get("cgFromNoseTipMm"), 0)));
        }
        return out;
    }

    public static double printedMassG() {
        return printedMassG(PrintMaterial.BASIC);
    }

    public static double printedMassG(PrintMaterial material) {
        double sum = 0;
        for (PartMass p : partTable(material)) sum += p.massG;
        return sum;
    }

    private static PartMass part(List<PartMass> t, String id) {
        for (PartMass p : t) if (p.id.equals(id)) return p;
        throw new IllegalStateException("part table has no entry '" + id + "'");
    }

    private static void override(RocketComponent c, PartMass p) {
        double startMm = c.getComponentLocations()[0].x * 1000.0;
        c.setMassOverridden(true);
        c.setOverrideMass(p.massG * massScale / 1000.0);
        c.setSubcomponentsOverriddenMass(false);
        c.setCGOverridden(true);
        c.setOverrideCGX((p.cgFromNoseTipMm - startMm) / 1000.0);
        c.setSubcomponentsOverriddenCG(false);
    }

    /** Builds the template document from CSWARCMOD1E. Returns a short report in {@code log}. */
    public static OpenRocketDocument build(File mod1eOrk, StringBuilder log) throws Exception {
        OpenRocketCore.initialize();
        OpenRocketDocument doc = new GeneralRocketLoader(mod1eOrk).load();
        Rocket rocket = doc.getRocket();
        rocket.setName(buildMaterial == PrintMaterial.BASIC ? DISPLAY_NAME : "ARC V2 (CAD template, " + buildMaterial.label + ")");
        List<PartMass> table = partTable(buildMaterial);

        RocketComponent stage = rocket.getChild(rocket.getChildCount() - 1);
        NoseCone nose = (NoseCone) stage.getChild(0);
        BodyTube upper = (BodyTube) stage.getChild(1);
        Transition trans = (Transition) stage.getChild(2);
        BodyTube lower = (BodyTube) stage.getChild(3);

        nose.setLength(CadModel.NOSE_LEN_M);
        nose.setAftRadius(CadModel.UPPER_RADIUS_M);
        nose.setThickness(0.00185);
        nose.setShapeType(Transition.Shape.ELLIPSOID);

        upper.setOuterRadiusAutomatic(true);
        upper.setLength(CadModel.UPPER_LEN_M);
        upper.setThickness(0.00085);

        trans.setLength(CadModel.TRANSITION_LEN_M);
        trans.setForeRadiusAutomatic(true);
        trans.setAftRadius(CadModel.LOWER_RADIUS_M);
        trans.setThickness(0.00085);
        fitTransitionShape(trans, log);

        lower.setOuterRadius(CadModel.LOWER_RADIUS_M);
        lower.setLength(CadModel.LOWER_LEN_M);
        lower.setThickness(0.00126);
        lower.setMotorOverhang(0.0105);

        // The CAD upper tube already contains its bulkhead ring, so the MOD1E bulkhead would be counted twice.
        for (int i = upper.getChildCount() - 1; i >= 0; i--) {
            if (upper.getChild(i) instanceof Bulkhead) {
                log.append("Removed MOD1E bulkhead from the upper tube (mass is inside the CAD upper tube).\n");
                upper.removeChild(i);
            }
        }

        TrapezoidFinSet fins = null;
        InnerTube mount = null;
        List<RailButton> buttons = new ArrayList<>();
        for (RocketComponent c : lower.getChildren()) {
            if (c instanceof TrapezoidFinSet f && fins == null) fins = f;
            else if (c instanceof InnerTube t && mount == null) mount = t;
            else if (c instanceof RailButton rb) buttons.add(rb);
        }
        if (fins == null || mount == null) throw new IllegalStateException("MOD1E layout changed: fin set or engine mount not found");

        fins.setFilletRadius(0.0);
        fins.setFinShape(CadModel.FIN_ROOT_M, CadModel.FIN_TIP_M, CadModel.FIN_SWEEP_M, CadModel.FIN_HEIGHT_M, CadModel.FIN_THICKNESS_M);

        mount.setOuterRadius(0.01643);
        mount.setThickness(0.01643 - 0.01475);
        mount.setLength(0.11875);
        mount.setAxialMethod(AxialMethod.BOTTOM);
        mount.setAxialOffset(-0.012);

        double[] buttonCentreFromAftM = {0.0375, 0.1725};
        for (int i = 0; i < buttons.size() && i < 2; i++) {
            RailButton rb = buttons.get(i);
            rb.setAxialMethod(AxialMethod.MIDDLE);
            rb.setAxialOffset(CadModel.LOWER_LEN_M / 2.0 - buttonCentreFromAftM[i]);
            rb.setMassOverridden(true);   // printed into the CAD lower tube
            rb.setOverrideMass(0.0);
        }

        override(nose, part(table, "nose"));
        override(upper, part(table, "upper"));
        override(trans, part(table, "transition"));
        override(lower, part(table, "lower"));
        override(fins, part(table, "fins"));
        override(mount, part(table, "motor_mount"));

        // Fresh simulation: keep MOD1E's launch conditions, drop its stale flight data.
        Simulation old = doc.getSimulationCount() > 0 ? doc.getSimulation(0) : null;
        Simulation fresh = new Simulation(doc, rocket);
        fresh.setName("Simulation 1");
        if (old != null) {
            fresh.setFlightConfigurationId(old.getFlightConfigurationId());
            fresh.getOptions().copyConditionsFrom(old.getOptions());
            doc.removeSimulation(old);
        }
        doc.addSimulation(fresh);
        return doc;
    }

    /** Picks the OpenRocket transition shape that best matches the CAD profile (the CAD curve is an S-curve). */
    private static void fitTransitionShape(Transition t, StringBuilder log) {
        // Outer radius (mm) measured from the CAD mid section, as distance from the small end.
        double[][] cad = {{5, 27.03}, {11, 27.12}, {17, 27.28}, {23, 27.52}, {29, 27.79}, {35, 28.16}, {41, 28.55},
                {47, 29.05}, {53, 29.61}, {59, 30.17}, {65, 30.73}, {71, 31.24}, {77, 31.69}, {83, 32.07},
                {89, 32.39}, {95, 32.64}, {101, 32.82}, {107, 32.94}, {113, 33.0}};
        Transition.Shape[] shapes = {Transition.Shape.CONICAL, Transition.Shape.OGIVE, Transition.Shape.ELLIPSOID,
                Transition.Shape.POWER, Transition.Shape.PARABOLIC, Transition.Shape.HAACK};
        double bestErr = Double.MAX_VALUE;
        Transition.Shape bestShape = Transition.Shape.CONICAL;
        double bestParam = 0;
        for (Transition.Shape s : shapes) {
            t.setShapeType(s);
            double lo = s.minParameter(), hi = s.maxParameter();
            int steps = (s.usesParameter() && hi > lo) ? 40 : 1;
            for (int k = 0; k < steps; k++) {
                double p = steps == 1 ? s.defaultParameter() : lo + (hi - lo) * k / (steps - 1);
                if (s.usesParameter()) t.setShapeParameter(p);
                double se = 0;
                for (double[] pt : cad) {
                    double d = t.getRadius(pt[0] / 1000.0) * 1000.0 - pt[1];
                    se += d * d;
                }
                double rms = Math.sqrt(se / cad.length);
                if (rms < bestErr) {
                    bestErr = rms;
                    bestShape = s;
                    bestParam = s.usesParameter() ? p : s.defaultParameter();
                }
            }
        }
        t.setShapeType(bestShape);
        if (bestShape.usesParameter()) t.setShapeParameter(bestParam);
        log.append(String.format("Transition shape: %s (parameter %.3f), RMS error vs CAD profile %.2f mm.%n",
                bestShape.name(), bestParam, bestErr));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            System.err.println("usage: CadTemplate <CSWARCMOD1E.ork> <out.ork> [basic|aero, default basic] [massScale, default 1.0]");
            System.exit(2);
        }
        if (args.length >= 3) buildMaterial = PrintMaterial.fromId(args[2]);
        if (args.length == 4) massScale = Double.parseDouble(args[3]);
        StringBuilder log = new StringBuilder();
        OpenRocketDocument doc = build(new File(args[0]), log);
        File out = new File(args[1]);
        new GeneralRocketSaver().save(out, doc);
        System.out.print(log);
        System.out.printf("Printed-part mass (%s, weighed %.1f g, extra scale %.2f): %.2f g%n", buildMaterial.label,
                buildMaterial.printedMassG, massScale, printedMassG(buildMaterial) * massScale);
        System.out.println("Wrote " + out.getAbsolutePath());
    }
}
