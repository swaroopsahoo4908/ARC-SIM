package com.arc.sim;

import info.openrocket.core.rocketcomponent.Rocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CadModelTest {

    static File template;
    static File mod1e = new File("OpenRocket/CSWARCMOD1E.ork");

    @BeforeAll
    static void setUp() throws Exception {
        System.setProperty("java.awt.headless", "true");
        MeshSource.setUseCadTransient(true);
        template = CadTemplate.extractBundled();
    }

    private static RocketGeometryExtractor.Geometry geo(File ork) throws Exception {
        Rocket r = new SimRunner(ork).getDocument().getRocket();
        return RocketGeometryExtractor.extract(r);
    }

    private static double signedVolumeMm3(CadModel.Flat f) {
        double v = 0;
        for (int i = 0; i < f.triCount(); i++) {
            int o = i * 9;
            double ax = f.xyz[o], ay = f.xyz[o + 1], az = f.xyz[o + 2];
            double bx = f.xyz[o + 3], by = f.xyz[o + 4], bz = f.xyz[o + 5];
            double cx = f.xyz[o + 6], cy = f.xyz[o + 7], cz = f.xyz[o + 8];
            v += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
        }
        return v / 6.0;
    }

    @Test
    void partTableMatchesPlaBasicDensity() {
        List<CadTemplate.PartMass> parts = CadTemplate.partTable();
        assertEquals(6, parts.size());
        for (CadTemplate.PartMass p : parts) {
            assertEquals(p.volumeMm3 * 1.24e-3 * (306.0 / 332.76), p.massG, 0.02, p.id);
        }
        assertEquals(306.0, CadTemplate.printedMassG(), 0.05);
    }

    @Test
    void plaAeroTemplateCarriesTheWeighedAeroMass() throws Exception {
        assertEquals(180.6, CadTemplate.printedMassG(PrintMaterial.AERO), 0.05);
        File aero = CadTemplate.extractBundled(PrintMaterial.AERO);
        Rocket rocket = new SimRunner(aero).getDocument().getRocket();
        double overridden = 0;
        for (var c : rocket.getSelectedConfiguration().getActiveComponents()) {
            if (c.isMassOverridden()) overridden += c.getMass();
        }
        assertEquals(0.1806, overridden, 1e-6);
        RocketGeometryExtractor.Geometry g = RocketGeometryExtractor.extract(rocket);
        assertNull(CadModel.get().mismatch(g), "Aero template uses the same CAD airframe");
        assertEquals(1, RocketComponents.findBallastComponents(rocket).size());
        SimRunner.FlightResult r = new SimRunner(aero).run(new EnvironmentPoint(0, 0, 0, 270, 15, 1013.25, 0, LaunchSite.MDRA_SOD_FARM));
        assertTrue(r.ok, r.error);
        System.out.printf("PLA Aero template still-air apogee %.1f m%n", r.apogeeM);
    }

    @Test
    void assembledMeshHasPositiveVolumeAndExpectedExtent() {
        CadModel m = CadModel.get();
        CadModel.Flat airframe = m.build(null, true);
        // Signed volume is positive only if every part kept outward winding through the frame change.
        double expected = 27085.0 + 39468.2 + 40464.2 + 57037.5 + 37749.3 + 4369.4;
        assertEquals(expected, signedVolumeMm3(airframe), expected * 0.02);

        double minX = 1e9, maxX = -1e9, maxR = 0;
        for (int i = 0; i < airframe.xyz.length; i += 3) {
            minX = Math.min(minX, airframe.xyz[i]);
            maxX = Math.max(maxX, airframe.xyz[i]);
            maxR = Math.max(maxR, Math.hypot(airframe.xyz[i + 1], airframe.xyz[i + 2]));
        }
        assertEquals(0.0, minX, 0.5);                         // nose tip at x = 0
        assertEquals(749.5, maxX, 0.5);                       // retainer cap stands 14.5 mm past the airframe
        assertEquals(39.44, maxR, 0.2);                       // rail button, the widest feature without fins
    }

    @Test
    void templateIsRecognisedAndSolverCompatible() throws Exception {
        SimRunner runner = new SimRunner(template);
        Rocket rocket = runner.getDocument().getRocket();
        RocketGeometryExtractor.Geometry g = RocketGeometryExtractor.extract(rocket);
        assertNull(CadModel.get().mismatch(g), "template must match the CAD airframe");
        assertEquals(0.735, g.totalLength, 1e-6);
        assertEquals(1, RocketComponents.findBallastComponents(rocket).size(), "exactly one ballast MassComponent");
        assertNotNull(RocketComponents.findMainParachute(rocket));
        assertEquals(0.070, RocketComponents.findFinSet(rocket).getHeight(), 1e-9);
        assertEquals(1, g.fins.size());
        assertEquals(4, g.fins.get(0).finCount);
    }

    @Test
    void templatePrintedMassesAreAppliedAsOverrides() throws Exception {
        Rocket rocket = new SimRunner(template).getDocument().getRocket();
        double overridden = 0;
        for (var c : rocket.getSelectedConfiguration().getActiveComponents()) {
            if (c.isMassOverridden()) overridden += c.getMass();
        }
        assertEquals(CadTemplate.printedMassG() / 1000.0, overridden, 1e-6);
    }

    @Test
    void templateFliesAndIsStable() throws Exception {
        RocketBuilderModel model = RocketBuilderModel.loadFromOrk(template);
        RocketBuilderModel.StabilityInfo st = model.computeStability();
        assertTrue(st.ok, st.error);
        System.out.printf("ARC V2 template: launch mass %.1f g, CG %.1f mm, CP %.1f mm, margin %.2f cal (%s)%n",
                st.massKg * 1000, st.cgXM * 1000, st.cpXM * 1000, st.marginCalibers, st.rating);
        assertTrue(st.marginCalibers >= 1.0, "template should be statically stable");

        SimRunner runner = new SimRunner(template);
        SimRunner.FlightResult r = runner.run(new EnvironmentPoint(0, 0, 0, 270, 15, 1013.25, 5, LaunchSite.MDRA_SOD_FARM));
        assertTrue(r.ok, r.error);
        System.out.printf("ARC V2 template still-air flight: apogee %.1f m, flight time %.1f s%n", r.apogeeM, r.flightTimeS);
        assertTrue(r.apogeeM > 50 && r.apogeeM < 1000);
    }

    @Test
    void finsConformToTheLoadedFinSet() throws Exception {
        RocketGeometryExtractor.Geometry g = geo(template);
        RocketGeometryExtractor.FinShape f = g.fins.get(0);
        f.height = 0.100;   // taller fins, as Engine 3 might solve for
        CadModel.Flat fins = CadModel.get().buildFins(List.of(f));
        double maxR = 0;
        for (int i = 0; i < fins.xyz.length; i += 9)
            for (int c = 0; c < 3; c++)
                maxR = Math.max(maxR, Math.hypot(fins.xyz[i + c * 3 + 1], fins.xyz[i + c * 3 + 2]));
        assertEquals(133.0, maxR, 1.0);   // 33 mm body radius + 100 mm fin height
    }

    @Test
    void otherRocketsFallBackToTheGeneratedMesh() throws Exception {
        assumeFileExists(mod1e);
        RocketGeometryExtractor.Geometry g = geo(mod1e);
        assertNotNull(CadModel.get().mismatch(g));
        List<MeshExporter.Triangle> viaCad = MeshExporter.buildMesh(g, true);
        List<MeshExporter.Triangle> generated = MeshExporter.buildGeneratedMesh(g);
        assertEquals(generated.size(), viaCad.size());
    }

    @Test
    void exportersUseTheCadWhenTheToggleIsOn() throws Exception {
        RocketGeometryExtractor.Geometry g = geo(template);
        List<MeshExporter.Triangle> cad = MeshExporter.buildMesh(g, true);
        List<MeshExporter.Triangle> generated = MeshExporter.buildMesh(g, false);
        assertTrue(cad.size() > generated.size() * 4, "CAD mesh is far denser than the generated one");
        File out = File.createTempFile("arc-v2", ".stl");
        out.deleteOnExit();
        MeshExporter.writeStl(cad, out, "ARC_V2");
        assertTrue(out.length() > 1_000_000);
    }

    @Test
    void rendersBothViews() throws Exception {
        RocketGeometryExtractor.Geometry g = geo(template);
        for (boolean cad : new boolean[]{true, false}) {
            MeshSource.setUseCadTransient(cad);
            RocketPreviewPanel p = new RocketPreviewPanel();
            p.setSize(900, 260);
            p.doLayout();
            p.setGeometry(g, "ARC V2 (CAD template)");
            BufferedImage img = new BufferedImage(900, 260, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = img.createGraphics();
            p.printAll(g2);
            g2.dispose();
            ImageIO.write(img, "png", new File("target/preview-" + (cad ? "cad" : "generated") + ".png"));
        }
        MeshSource.setUseCadTransient(true);
    }

    private static void assumeFileExists(File f) {
        org.junit.jupiter.api.Assumptions.assumeTrue(f.isFile(), f + " not found");
    }
}
