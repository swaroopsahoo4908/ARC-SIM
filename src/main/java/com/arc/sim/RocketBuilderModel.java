package com.arc.sim;

import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.file.motor.GeneralMotorLoader;
import info.openrocket.core.material.Material;
import info.openrocket.core.motor.Manufacturer;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.*;
import info.openrocket.core.startup.Application;
import info.openrocket.core.startup.OpenRocketCore;
import info.openrocket.core.util.Coordinate;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Non-GUI model layer for the Rocket Builder tab: creating/loading/saving an OpenRocketDocument,
 * adding/removing/repositioning components on its Rocket tree, a curated material catalog, and
 * motor-mount assignment against the bundled thrust-curve motor database. Wraps info.openrocket.core
 * directly (no GUI dependency) so it can be exercised headlessly in tests.
 */
public class RocketBuilderModel {

    private final OpenRocketDocument document;
    private final Rocket rocket;
    private FlightConfigurationId activeFcid;

    static {
        OpenRocketCore.initialize();
    }

    private RocketBuilderModel(OpenRocketDocument document, FlightConfigurationId activeFcid) {
        this.document = document;
        this.rocket = document.getRocket();
        this.activeFcid = activeFcid;
    }

    public static RocketBuilderModel newRocket() {
        OpenRocketDocument doc = OpenRocketDocumentFactory.createNewRocket();
        Rocket rocket = doc.getRocket();
        FlightConfigurationId fcid = new FlightConfigurationId();
        rocket.createFlightConfiguration(fcid);
        rocket.setSelectedConfiguration(fcid);

        Simulation sim = new Simulation(doc, rocket);
        sim.setName("Simulation 1");
        sim.setFlightConfigurationId(fcid);
        doc.addSimulation(sim);

        return new RocketBuilderModel(doc, fcid);
    }

    public static RocketBuilderModel loadFromOrk(File orkFile) throws Exception {
        GeneralRocketLoader loader = new GeneralRocketLoader(orkFile);
        OpenRocketDocument doc = loader.load();
        Rocket rocket = doc.getRocket();

        FlightConfigurationId fcid;
        if (doc.getSimulationCount() > 0) {
            fcid = doc.getSimulation(0).getFlightConfigurationId();
        } else {
            fcid = new FlightConfigurationId();
            rocket.createFlightConfiguration(fcid);
            Simulation sim = new Simulation(doc, rocket);
            sim.setName("Simulation 1");
            sim.setFlightConfigurationId(fcid);
            doc.addSimulation(sim);
        }
        rocket.setSelectedConfiguration(fcid);

        return new RocketBuilderModel(doc, fcid);
    }

    public void saveToOrk(File out) throws Exception {
        new GeneralRocketSaver().save(out, document);
    }

    public OpenRocketDocument getDocument() {
        return document;
    }

    public Rocket getRocket() {
        return rocket;
    }

    public FlightConfigurationId getActiveFcid() {
        return activeFcid;
    }

    public RocketGeometryExtractor.Geometry previewGeometry() {
        return RocketGeometryExtractor.extract(rocket);
    }

    // ---------------------------------------------------------------------
    // Component tree mutation
    // ---------------------------------------------------------------------

    public enum ComponentType {
        AXIAL_STAGE("Stage", AxialStage.class, AxialStage::new),
        NOSE_CONE("Nose Cone", NoseCone.class, () -> {
            NoseCone nc = new NoseCone();
            nc.setLength(0.25);
            nc.setBaseRadius(0.033);
            nc.setThickness(0.0015);
            nc.setMaterial(MaterialCatalog.defaultBulk());
            return nc;
        }),
        BODY_TUBE("Body Tube", BodyTube.class, () -> {
            BodyTube bt = new BodyTube();
            bt.setLength(0.3);
            bt.setOuterRadius(0.033);
            bt.setThickness(0.0015);
            bt.setMaterial(MaterialCatalog.defaultBulk());
            return bt;
        }),
        TRANSITION("Transition", Transition.class, () -> {
            Transition t = new Transition();
            t.setLength(0.1);
            t.setForeRadius(0.033);
            t.setAftRadius(0.02);
            t.setThickness(0.0015);
            t.setMaterial(MaterialCatalog.defaultBulk());
            return t;
        }),
        TRAPEZOID_FIN_SET("Trapezoid Fin Set", TrapezoidFinSet.class, () -> {
            TrapezoidFinSet f = new TrapezoidFinSet();
            f.setFinCount(4);
            f.setRootChord(0.15);
            f.setTipChord(0.05);
            f.setSweep(0.08);
            f.setHeight(0.08);
            f.setThickness(0.003);
            f.setMaterial(MaterialCatalog.defaultBulk());
            return f;
        }),
        ELLIPTICAL_FIN_SET("Elliptical Fin Set", EllipticalFinSet.class, () -> {
            EllipticalFinSet f = new EllipticalFinSet();
            f.setFinCount(4);
            f.setLength(0.15);
            f.setHeight(0.08);
            f.setThickness(0.003);
            f.setMaterial(MaterialCatalog.defaultBulk());
            return f;
        }),
        LAUNCH_LUG("Launch Lug", LaunchLug.class, () -> {
            LaunchLug l = new LaunchLug();
            l.setLength(0.03);
            l.setOuterRadius(0.0035);
            l.setThickness(0.0007);
            l.setMaterial(MaterialCatalog.defaultBulk());
            return l;
        }),
        RAIL_BUTTON("Rail Button", RailButton.class, RailButton::new),
        INNER_TUBE("Inner Tube (Motor Mount)", InnerTube.class, () -> {
            InnerTube it = new InnerTube();
            it.setLength(0.28);
            it.setOuterRadius(0.0145);
            it.setThickness(0.001);
            it.setMotorMount(true);
            it.setMaterial(MaterialCatalog.defaultBulk());
            return it;
        }),
        CENTERING_RING("Centering Ring", CenteringRing.class, () -> {
            CenteringRing r = new CenteringRing();
            r.setOuterRadius(0.033);
            r.setInnerRadius(0.0145);
            r.setThickness(0.003);
            r.setMaterial(MaterialCatalog.defaultBulk());
            return r;
        }),
        BULKHEAD("Bulkhead", Bulkhead.class, () -> {
            Bulkhead b = new Bulkhead();
            b.setOuterRadius(0.033);
            b.setThickness(0.006);
            b.setMaterial(MaterialCatalog.defaultBulk());
            return b;
        }),
        TUBE_COUPLER("Tube Coupler", TubeCoupler.class, () -> {
            TubeCoupler c = new TubeCoupler();
            c.setLength(0.08);
            c.setOuterRadius(0.031);
            c.setThickness(0.0015);
            c.setMaterial(MaterialCatalog.defaultBulk());
            return c;
        }),
        ENGINE_BLOCK("Engine Block", EngineBlock.class, () -> {
            EngineBlock e = new EngineBlock();
            e.setOuterRadius(0.0145);
            e.setInnerRadius(0.011);
            e.setThickness(0.0035);
            e.setMaterial(MaterialCatalog.defaultBulk());
            return e;
        }),
        MASS_COMPONENT("Mass Component (Ballast/Avionics)", MassComponent.class, () -> {
            MassComponent m = new MassComponent();
            m.setComponentMass(0.05);
            m.setLength(0.02);
            m.setRadius(0.02);
            return m;
        }),
        PARACHUTE("Parachute", Parachute.class, () -> {
            Parachute p = new Parachute();
            p.setDiameter(0.4);
            p.setMaterial(MaterialCatalog.defaultSurface());
            p.setLineMaterial(MaterialCatalog.defaultLine());
            return p;
        }),
        STREAMER("Streamer", Streamer.class, () -> {
            Streamer s = new Streamer();
            s.setStripLength(0.6);
            s.setStripWidth(0.075);
            s.setMaterial(MaterialCatalog.defaultSurface());
            return s;
        }),
        SHOCK_CORD("Shock Cord", ShockCord.class, () -> {
            ShockCord s = new ShockCord();
            s.setCordLength(1.2);
            s.setMaterial(MaterialCatalog.defaultLine());
            return s;
        });

        public final String displayName;
        public final Class<? extends RocketComponent> componentClass;
        public final Supplier<? extends RocketComponent> factory;

        ComponentType(String displayName, Class<? extends RocketComponent> componentClass,
                      Supplier<? extends RocketComponent> factory) {
            this.displayName = displayName;
            this.componentClass = componentClass;
            this.factory = factory;
        }

        public boolean isAddableTo(RocketComponent parent) {
            if (parent == null) return false;
            if (!parent.allowsChildren()) return false;
            try {
                return parent.isCompatible(componentClass);
            } catch (Exception e) {
                return false;
            }
        }
    }

    public RocketComponent addComponent(RocketComponent parent, ComponentType type) {
        RocketComponent child = type.factory.get();
        parent.addChild(child);
        return child;
    }

    public void removeComponent(RocketComponent c) {
        RocketComponent parent = c.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Cannot remove the rocket root.");
        }
        parent.removeChild(c);
    }

    public void moveComponentUp(RocketComponent c) {
        RocketComponent parent = c.getParent();
        if (parent == null) return;
        int idx = parent.getChildPosition(c);
        if (idx > 0) parent.moveChild(c, idx - 1);
    }

    public void moveComponentDown(RocketComponent c) {
        RocketComponent parent = c.getParent();
        if (parent == null) return;
        int idx = parent.getChildPosition(c);
        if (idx < parent.getChildCount() - 1) parent.moveChild(c, idx + 1);
    }

    // ---------------------------------------------------------------------
    // Materials
    // ---------------------------------------------------------------------

    /**
     * Curated, top-of-the-line material catalog. Densities are typical reference values for the
     * material class/grade named (aerospace-grade where a grade is named) -- treat as design-stage
     * estimates, not a certified materials database. BULK = kg/m^3 (structural solids), SURFACE =
     * kg/m^2 (canopy fabrics), LINE = kg/m (cords/shroud lines).
     */
    public static final class MaterialCatalog {
        private static final List<Material> BULK = new ArrayList<>();
        private static final List<Material> SURFACE = new ArrayList<>();
        private static final List<Material> LINE = new ArrayList<>();

        static {
            bulk("Carbon Fiber, Woven (CFRP)", 1550.0);
            bulk("Fiberglass, G10/FR4", 1850.0);
            bulk("Aircraft Plywood, Birch", 630.0);
            bulk("Kraft Phenolic Tubing", 950.0);
            bulk("Basswood", 450.0);
            bulk("Balsa", 170.0);
            bulk("Cardboard, Spiral-Wound", 680.0);
            bulk("Aluminum 6061-T6", 2700.0);
            bulk("Aluminum 7075-T6", 2810.0);
            bulk("Titanium, Ti-6Al-4V", 4430.0);
            bulk("Stainless Steel, 17-4 PH", 7750.0);
            bulk("ABS Plastic", 1050.0);
            bulk("PLA (3D-Printed)", 1250.0);
            bulk("Nylon 6/6", 1150.0);
            bulk("Polycarbonate", 1200.0);

            surface("Ripstop Nylon", 0.067);
            surface("Mylar (thin)", 0.019);
            surface("Icarex Polyester", 0.038);
            surface("Silicone-Coated Nylon", 0.085);

            line("Kevlar Cord, 1/8 in", 0.0049);
            line("Kevlar Cord, 1/4 in", 0.0098);
            line("Tubular Nylon, 1/2 in", 0.0085);
            line("Paracord, 550", 0.0068);
            line("Braided Nylon Shock Cord", 0.0060);
        }

        private static void bulk(String name, double densityKgM3) {
            BULK.add(Material.newMaterial(Material.Type.BULK, name, densityKgM3, true));
        }

        private static void surface(String name, double densityKgM2) {
            SURFACE.add(Material.newMaterial(Material.Type.SURFACE, name, densityKgM2, true));
        }

        private static void line(String name, double densityKgM) {
            LINE.add(Material.newMaterial(Material.Type.LINE, name, densityKgM, true));
        }

        public static List<Material> bulkMaterials() {
            return List.copyOf(BULK);
        }

        public static List<Material> surfaceMaterials() {
            return List.copyOf(SURFACE);
        }

        public static List<Material> lineMaterials() {
            return List.copyOf(LINE);
        }

        public static Material defaultBulk() {
            return BULK.get(0);
        }

        public static Material defaultSurface() {
            return SURFACE.get(0);
        }

        public static Material defaultLine() {
            return LINE.get(0);
        }

        /**
         * Adds a user-defined material to the catalog (persists for the rest of this session, across
         * any rocket open in the Rocket Builder). Density units follow the type: kg/m^3 for BULK,
         * kg/m^2 for SURFACE, kg/m for LINE.
         */
        public static Material addCustom(Material.Type type, String name, double density) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Material name can't be blank.");
            }
            if (density <= 0) {
                throw new IllegalArgumentException("Density must be positive.");
            }
            Material m = Material.newMaterial(type, name.trim(), density, true);
            switch (type) {
                case BULK -> BULK.add(m);
                case SURFACE -> SURFACE.add(m);
                case LINE -> LINE.add(m);
                default -> throw new IllegalArgumentException("Unsupported material type: " + type);
            }
            return m;
        }

        public static List<Material> materialsFor(Material.Type type) {
            return switch (type) {
                case BULK -> bulkMaterials();
                case SURFACE -> surfaceMaterials();
                case LINE -> lineMaterials();
                default -> List.of();
            };
        }
    }

    // ---------------------------------------------------------------------
    // Motor database / assignment
    // ---------------------------------------------------------------------

    public List<ThrustCurveMotorSet> searchMotorSets(String query, Double mountDiameterM, double diameterToleranceM) {
        ThrustCurveMotorSetDatabase db = Application.getThrustCurveMotorSetDatabase();
        String q = query == null ? "" : query.trim().toLowerCase();
        List<ThrustCurveMotorSet> out = new ArrayList<>();
        for (ThrustCurveMotorSet ms : db.getMotorSets()) {
            if (mountDiameterM != null && Math.abs(ms.getDiameter() - mountDiameterM) > diameterToleranceM) {
                continue;
            }
            if (!q.isEmpty()) {
                String hay = (ms.getManufacturer() + " " + ms.getDesignation() + " " + ms.getCommonName()).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            out.add(ms);
        }
        out.sort(Comparator.comparingDouble(ThrustCurveMotorSet::getDiameter)
                .thenComparing(ms -> ms.getManufacturer().toString())
                .thenComparingLong(ThrustCurveMotorSet::getTotalImpulse));
        return out;
    }

    /** Session-wide custom/imported motors (not part of the bundled thrust-curve database). */
    private static final List<ThrustCurveMotor> CUSTOM_MOTORS = new ArrayList<>();

    public static List<ThrustCurveMotor> customMotors() {
        return List.copyOf(CUSTOM_MOTORS);
    }

    /**
     * Builds a custom motor from hand-entered specs using an idealized trapezoidal thrust curve
     * (10% ramp-up / 80% plateau / 10% tail-off) that reproduces the given total impulse exactly.
     * This is a design-stage approximation, not a certified thrust curve -- for a real motor's
     * actual data, use {@link #importMotorFile}.
     */
    public static ThrustCurveMotor createCustomMotor(String designation, String manufacturerName,
            Motor.Type type, double diameterM, double lengthM, double burnTimeS, double totalImpulseNs,
            double initialMassKg, double propellantMassKg, double[] delaysS) {
        if (designation == null || designation.isBlank()) {
            throw new IllegalArgumentException("Designation can't be blank.");
        }
        if (burnTimeS <= 0 || totalImpulseNs <= 0 || diameterM <= 0 || lengthM <= 0) {
            throw new IllegalArgumentException("Burn time, total impulse, diameter, and length must all be positive.");
        }
        if (propellantMassKg >= initialMassKg) {
            throw new IllegalArgumentException("Propellant mass must be less than initial (loaded) mass.");
        }

        double burnoutMass = initialMassKg - propellantMassKg;
        double peakThrust = totalImpulseNs / (0.9 * burnTimeS);
        double[] t = {0.0, 0.1 * burnTimeS, 0.9 * burnTimeS, burnTimeS};
        double[] thrust = {0.0, peakThrust, peakThrust, 0.0};
        Coordinate[] cg = new Coordinate[t.length];
        for (int i = 0; i < t.length; i++) {
            double mass = initialMassKg - (initialMassKg - burnoutMass) * (t[i] / burnTimeS);
            cg[i] = new Coordinate(lengthM / 2.0, 0, 0, mass);
        }

        ThrustCurveMotor.Builder b = new ThrustCurveMotor.Builder();
        b.setManufacturer(Manufacturer.getManufacturer(
                (manufacturerName == null || manufacturerName.isBlank()) ? "Custom" : manufacturerName.trim()));
        b.setDesignation(designation.trim());
        b.setCommonName(designation.trim());
        b.setDiameter(diameterM);
        b.setLength(lengthM);
        b.setMotorType(type == null ? Motor.Type.UNKNOWN : type);
        b.setStandardDelays(delaysS != null && delaysS.length > 0 ? delaysS : new double[]{0.0});
        b.setTimePoints(t);
        b.setThrustPoints(thrust);
        b.setCGPoints(cg);
        b.setInitialMass(initialMassKg);
        b.setCaseInfo("Custom");
        b.setPropellantInfo("Custom");
        b.setDescription("Custom motor defined in Arc-Sim Rocket Builder (idealized trapezoidal thrust curve, " +
                "reproduces the specified total impulse and burn time exactly; not a certified thrust curve).");
        b.setDigest("custom-" + designation.trim() + "-" + System.nanoTime());
        ThrustCurveMotor motor = b.build();
        registerMotor(motor);
        return motor;
    }

    /**
     * Registers a motor into both our own session-list (for display in the picker) and OpenRocket's
     * actual runtime motor database. The second part matters: a .ork file stores motor references by
     * digest/manufacturer/designation and re-resolves them against the running app's motor database
     * on load -- skip this and a saved rocket's custom motor silently reverts to "no motor" the moment
     * it's reloaded (including by this same Rocket Builder's own Save -> reload round-trip). Custom
     * motors only persist for the lifetime of this running session; re-define or re-import them after
     * restarting the app if a saved rocket needs them again.
     */
    private static void registerMotor(ThrustCurveMotor motor) {
        CUSTOM_MOTORS.add(motor);
        Application.getThrustCurveMotorSetDatabase().addMotor(motor);
    }

    /**
     * Imports one or more real motors from a RASP (.eng), RockSim (.rse), or zipped thrust-curve
     * file via OpenRocket's own motor-file loader, and adds them to the session's custom motor list.
     */
    public static List<ThrustCurveMotor> importMotorFile(File file) throws Exception {
        GeneralMotorLoader loader = new GeneralMotorLoader();
        List<ThrustCurveMotor> imported = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file)) {
            List<ThrustCurveMotor.Builder> builders = loader.load(fis, file.getName());
            for (ThrustCurveMotor.Builder b : builders) {
                ThrustCurveMotor m = b.build();
                imported.add(m);
                registerMotor(m);
            }
        }
        if (imported.isEmpty()) {
            throw new IllegalStateException("No motors found in " + file.getName() + " -- expected a .eng, .rse, or zipped thrust-curve file.");
        }
        return imported;
    }

    public void assignMotor(MotorMount mount, ThrustCurveMotor motor) {
        MotorConfiguration mc = mount.getMotorConfig(activeFcid);
        mc.setMotor(motor);
        mc.useDefaultIgnition();
        mount.setMotorConfig(mc, activeFcid);
        mount.setMotorMount(true);
    }

    public void clearMotor(MotorMount mount) {
        MotorConfiguration mc = mount.getMotorConfig(activeFcid);
        mc.setMotor(null);
        mount.setMotorConfig(mc, activeFcid);
    }
}
