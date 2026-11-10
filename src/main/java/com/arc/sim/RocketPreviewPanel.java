package com.arc.sim;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.GeneralPath;
import java.util.Arrays;
import java.util.List;

public class RocketPreviewPanel extends JPanel {

    private RocketGeometryExtractor.Geometry geometry;
    private String rocketName = "";
    private boolean showPointMasses = true;

    // ARC V2 CAD view (null when the generated schematic is shown).
    private CadModel.Flat cadScene;
    private String cadFallbackReason;
    private double viewRoll = 0.55, viewTilt = 0.30, viewZoom = 1.0;
    private int dragX, dragY;
    private boolean dragging;
    private final JCheckBox cadToggle = new JCheckBox("ARC V2 CAD model");
    private final Runnable settingListener = this::onSettingChanged;
    private final javax.swing.JComboBox<PrintMaterial> materialBox = new javax.swing.JComboBox<>(PrintMaterial.values());

    public RocketPreviewPanel() {
        super(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        cadToggle.setOpaque(false);
        cadToggle.setFocusable(false);
        cadToggle.setSelected(MeshSource.useCad());
        cadToggle.setToolTipText("On: show and export the ARC V2 CAD model. Off: the generated body-of-revolution mesh.");
        cadToggle.addActionListener(e -> MeshSource.setUseCad(cadToggle.isSelected()));
        add(cadToggle);
        materialBox.setFocusable(false);
        materialBox.setSelectedItem(PrintMaterial.current());
        materialBox.setToolTipText("Print material: sets the printed mass shown here and the template New Rocket starts from.");
        materialBox.addActionListener(e -> {
            PrintMaterial m = (PrintMaterial) materialBox.getSelectedItem();
            if (m != null) PrintMaterial.setCurrent(m);
        });
        add(materialBox);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragX = e.getX();
                dragY = e.getY();
                dragging = cadScene != null;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (cadScene == null) return;
                viewRoll += (e.getX() - dragX) * 0.012;
                viewTilt = Math.max(-1.4, Math.min(1.4, viewTilt + (e.getY() - dragY) * 0.012));
                dragX = e.getX();
                dragY = e.getY();
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (cadScene != null && e.getClickCount() == 2) {
                    viewRoll = 0.55;
                    viewTilt = 0.30;
                    viewZoom = 1.0;
                    repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (cadScene == null) return;
                viewZoom = Math.max(0.4, Math.min(6.0, viewZoom * Math.pow(1.1, -e.getPreciseWheelRotation())));
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        MeshSource.addListener(settingListener);
        PrintMaterial.addListener(settingListener);
        onSettingChanged();
    }

    @Override
    public void removeNotify() {
        MeshSource.removeListener(settingListener);
        PrintMaterial.removeListener(settingListener);
        super.removeNotify();
    }

    private void onSettingChanged() {
        SwingUtilities.invokeLater(() -> {
            cadToggle.setSelected(MeshSource.useCad());
            materialBox.setSelectedItem(PrintMaterial.current());
            rebuildCadScene();
            repaint();
        });
    }

    private void rebuildCadScene() {
        cadScene = null;
        cadFallbackReason = null;
        if (geometry == null || !MeshSource.useCad()) return;
        try {
            CadModel model = CadModel.get();
            String why = model.mismatch(geometry);
            if (why == null) cadScene = model.build(geometry, false);
            else cadFallbackReason = why;
        } catch (Throwable t) {
            cadFallbackReason = "the CAD meshes could not be loaded (" + t.getClass().getSimpleName() + ")";
        }
    }

    public void setGeometry(RocketGeometryExtractor.Geometry geometry, String rocketName) {
        this.geometry = geometry;
        this.rocketName = rocketName;
        rebuildCadScene();
        repaint();
    }

    public void setShowPointMasses(boolean showPointMasses) {
        this.showPointMasses = showPointMasses;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, getWidth(), getHeight());

        if (geometry == null || geometry.totalLength <= 0) {
            g.setColor(Color.GRAY);
            g.drawString("No rocket loaded yet.", 16, 24);
            return;
        }

        int margin = 40;
        int w = getWidth() - 2 * margin;
        int h = getHeight() - 2 * margin;
        if (w <= 10 || h <= 10) return;

        if (cadScene != null) {
            paintCad(g, margin);
            return;
        }

        double maxDim = Math.max(geometry.totalLength, geometry.maxRadius * 2 * 1.6);
        double scale = Math.min(w / geometry.totalLength, h / (geometry.maxRadius * 2 * 2.2));
        int centerY = getHeight() / 2;

        g.setColor(new Color(40, 60, 90));
        g.setStroke(new BasicStroke(1.5f));
        g.setFont(g.getFont().deriveFont(11f));

        for (RocketGeometryExtractor.BodyShape s : geometry.bodies) {
            double[] profile = s.profileR;
            int stations = profile.length;
            double dx = s.length / (double) (stations - 1);

            GeneralPath top = new GeneralPath();
            GeneralPath bottom = new GeneralPath();
            for (int i = 0; i < stations; i++) {
                int x = margin + (int) ((s.xStart + i * dx) * scale);
                int rTop = centerY - (int) (profile[i] * scale);
                int rBot = centerY + (int) (profile[i] * scale);
                if (i == 0) { top.moveTo(x, rTop); bottom.moveTo(x, rBot); }
                else { top.lineTo(x, rTop); bottom.lineTo(x, rBot); }
            }

            GeneralPath path = new GeneralPath(top);
            for (int i = stations - 1; i >= 0; i--) {
                int x = margin + (int) ((s.xStart + i * dx) * scale);
                int rBot = centerY + (int) (profile[i] * scale);
                path.lineTo(x, rBot);
            }
            path.closePath();

            g.setColor(new Color(225, 235, 245));
            g.fill(path);
            g.setColor(new Color(40, 60, 90));
            g.draw(path);
        }

        for (RocketGeometryExtractor.FinShape f : geometry.fins) {
            int rootX1 = margin + (int) (f.xStart * scale);
            int rootX2 = margin + (int) ((f.xStart + f.rootChord) * scale);
            int tipX1 = margin + (int) ((f.xStart + f.sweep) * scale);
            int tipX2 = margin + (int) ((f.xStart + f.sweep + f.tipChord) * scale);
            int bodyTop = centerY - (int) (f.parentRadius * scale);
            int bodyBottom = centerY + (int) (f.parentRadius * scale);
            int finTipTop = bodyTop - (int) (f.height * scale);
            int finTipBottom = bodyBottom + (int) (f.height * scale);

            g.setColor(new Color(200, 90, 60, 180));
            GeneralPath top = new GeneralPath();
            top.moveTo(rootX1, bodyTop);
            top.lineTo(tipX1, finTipTop);
            top.lineTo(tipX2, finTipTop);
            top.lineTo(rootX2, bodyTop);
            top.closePath();
            g.fill(top);
            g.setColor(new Color(140, 50, 30));
            g.draw(top);

            g.setColor(new Color(200, 90, 60, 180));
            GeneralPath bottom = new GeneralPath();
            bottom.moveTo(rootX1, bodyBottom);
            bottom.lineTo(tipX1, finTipBottom);
            bottom.lineTo(tipX2, finTipBottom);
            bottom.lineTo(rootX2, bodyBottom);
            bottom.closePath();
            g.fill(bottom);
            g.setColor(new Color(140, 50, 30));
            g.draw(bottom);
        }

        for (RocketGeometryExtractor.AppendageShape a : geometry.appendages) {
            int cx = margin + (int) (a.xCenter * scale);
            int halfLen = Math.max(1, (int) (a.lengthAlongAxis * scale / 2));
            int bodyTop = centerY - (int) (a.parentRadius * scale);
            int protrusion = Math.max(2, (int) (a.protrusionHeight * scale));
            Color fill = a.kind == RocketGeometryExtractor.AppendageKind.RAIL_BUTTON
                    ? new Color(60, 140, 70, 220) : new Color(90, 90, 170, 220);
            int rectW = Math.max(2, halfLen * 2);
            g.setColor(fill);
            g.fillRect(cx - halfLen, bodyTop - protrusion, rectW, protrusion);
            g.setColor(fill.darker());
            g.drawRect(cx - halfLen, bodyTop - protrusion, rectW, protrusion);
        }

        if (showPointMasses && !geometry.pointMasses.isEmpty()) {
            int bodyBottom = centerY + (int) (geometry.maxRadius * scale);
            int tickBaseY = Math.min(getHeight() - 46, bodyBottom + 16);
            g.setFont(g.getFont().deriveFont(9f));
            boolean altRow = false;
            for (RocketGeometryExtractor.PointMassShape m : geometry.pointMasses) {
                int cx = margin + (int) (m.xCenter * scale);
                int y = tickBaseY + (altRow ? 13 : 0);
                g.setColor(new Color(130, 85, 170));
                g.fillOval(cx - 3, y - 3, 6, 6);
                g.drawString(m.label + String.format(" (%.0fg)", m.massKg * 1000.0), cx + 5, y + 3);
                altRow = !altRow;
            }
        }

        g.setColor(Color.DARK_GRAY);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString(rocketName + "   (length \u2248 " + String.format("%.2f", geometry.totalLength) + " m, "
                + "max diameter \u2248 " + String.format("%.3f", geometry.maxRadius * 2) + " m)", margin, 20);
        g.setFont(g.getFont().deriveFont(Font.ITALIC, 10f));
        g.setColor(Color.GRAY);
        String footer = "Generated schematic (body of revolution + flat fins), approximate axial positions. Serial staging only.";
        if (cadFallbackReason != null) {
            footer = "ARC V2 CAD model not shown because " + cadFallbackReason + ". Showing the generated schematic.";
        }
        g.drawString(footer, margin, getHeight() - 10);

        if (!geometry.skipped.isEmpty()) {
            g.setColor(new Color(180, 120, 0));
            g.drawString("Not rendered (unsupported/unreadable): " + String.join(", ", geometry.skipped), margin, 34);
        }
    }

    private void paintCad(Graphics2D g, int margin) {
        final float[] xyz = cadScene.xyz;
        final byte[] cls = cadScene.cls;
        final int n = cadScene.triCount();
        double cr = Math.cos(viewRoll), sr = Math.sin(viewRoll), ct = Math.cos(viewTilt), st = Math.sin(viewTilt);
        double xc = geometry.totalLength * 500.0;                         // mid-length in mm
        double scale = Math.min((getWidth() - 2.0 * margin) / (CadModel.LENGTH_MM * 1.03),
                (getHeight() - 2.0 * margin) / 170.0) * viewZoom;
        double cx = getWidth() / 2.0, cy = getHeight() / 2.0 + 4;
        int step = dragging && n > 30000 ? 3 : 1;

        double[] px = new double[n * 3], py = new double[n * 3], pz = new double[n * 3];
        long[] order = new long[n];
        int visible = 0;
        double lx = -0.35, ly = 0.55, lz = 0.76;                          // light direction in view space
        float[] shade = new float[n];
        for (int i = 0; i < n; i += step) {
            double[] sx = new double[3], sy = new double[3], sz = new double[3];
            for (int c = 0; c < 3; c++) {
                double x = xyz[i * 9 + c * 3] - xc, y = xyz[i * 9 + c * 3 + 1], z = xyz[i * 9 + c * 3 + 2];
                double y1 = y * cr - z * sr, z1 = y * sr + z * cr;        // roll about the rocket axis
                sx[c] = x * ct + z1 * st;                                  // tilt about the view's vertical axis
                sz[c] = -x * st + z1 * ct;
                sy[c] = y1;
            }
            double ux = sx[1] - sx[0], uy = sy[1] - sy[0], uz = sz[1] - sz[0];
            double vx = sx[2] - sx[0], vy = sy[2] - sy[0], vz = sz[2] - sz[0];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12 || nz <= 0) continue;                          // back-face
            double lam = Math.max(0, (nx * lx + ny * ly + nz * lz) / len);
            shade[i] = (float) (0.32 + 0.68 * lam);
            for (int c = 0; c < 3; c++) {
                px[i * 3 + c] = cx + sx[c] * scale;
                py[i * 3 + c] = cy - sy[c] * scale;
            }
            double depth = (sz[0] + sz[1] + sz[2]) / 3.0;
            order[visible++] = ((long) ((depth + 2000.0) * 1000.0) << 24) | i;
        }
        Arrays.sort(order, 0, visible);

        int[] xs = new int[3], ys = new int[3];
        for (int k = 0; k < visible; k++) {
            int i = (int) (order[k] & 0xFFFFFF);
            float sh = shade[i];
            Color base = cls[i] == CadModel.CLASS_FIN ? new Color(214, 96, 64)
                    : cls[i] == CadModel.CLASS_INTERNAL ? new Color(120, 124, 132) : new Color(196, 206, 222);
            g.setColor(new Color(Math.min(255, (int) (base.getRed() * sh)), Math.min(255, (int) (base.getGreen() * sh)),
                    Math.min(255, (int) (base.getBlue() * sh))));
            for (int c = 0; c < 3; c++) {
                xs[c] = (int) Math.round(px[i * 3 + c]);
                ys[c] = (int) Math.round(py[i * 3 + c]);
            }
            g.fillPolygon(xs, ys, 3);
        }

        g.setColor(Color.DARK_GRAY);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString(rocketName + "   (length " + String.format("%.0f", geometry.totalLength * 1000.0) + " mm, max diameter "
                + String.format("%.0f", geometry.maxRadius * 2000.0) + " mm, printed mass "
                + String.format("%.1f", PrintMaterial.current().printedMassG) + " g, " + PrintMaterial.current().label + ")", margin, 20);
        g.setFont(g.getFont().deriveFont(Font.ITALIC, 10f));
        g.setColor(Color.GRAY);
        g.drawString("ARC V2 CAD model. Drag to rotate, wheel to zoom, double-click to reset. Fins follow the loaded fin set.",
                margin, getHeight() - 10);
    }
}

