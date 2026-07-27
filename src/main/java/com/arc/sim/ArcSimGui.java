package com.arc.sim;

import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ArcSimGui extends JFrame {

    private final JTextArea log = new JTextArea();
    private final JLabel statusLabel = new JLabel("Idle");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel etaLabel = new JLabel(" ");
    private final JButton cancelButton = new JButton("Cancel");
    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arc-sim-job");
        t.setDaemon(true);
        return t;
    });
    private volatile Future<?> currentJob;

    private static String weatherApiKey() {
        return AppConfig.get().weatherApiKey;
    }

    private static File lastDir = AppConfig.appDir();

    public static final String APP_VERSION = "1.1.0";

    public ArcSimGui() {
        super("Arc-Sim -- Rocket Simulation Toolkit");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                AppConfig.get().save();
            }
        });
        setSize(960, 780);
        setMinimumSize(new java.awt.Dimension(800, 600));
        setLocationRelativeTo(null);
        setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);

        Image appIcon = loadAppIcon();
        if (appIcon != null) setIconImage(appIcon);

        setJMenuBar(buildMenuBar());

        redirectSystemStreamsToLog();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Engine 1: Full Factorial Sweep", buildFullSweepTab());
        tabs.addTab("Engine 2: Design Solver", buildDesignTab());
        tabs.addTab("Engine 3: Geometry Export", buildGeometryExportTab());
        tabs.addTab("Engine 4: Weather-Driven Design", buildWeatherTab());
        tabs.addTab("Data Viewer", new DataViewerPanel());

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBackground(new Color(0x15, 0x15, 0x19));
        log.setForeground(new Color(0xe8, 0xe8, 0xec));
        log.setCaretColor(new Color(0xff, 0x7a, 0x3d));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        logScroll.setPreferredSize(new Dimension(900, 230));

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(220, 20));

        JPanel statusBar = new JPanel(new BorderLayout(8, 0));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x3c, 0x3c, 0x48)),
                new EmptyBorder(6, 8, 6, 8)));
        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftStatus.add(statusLabel);
        leftStatus.add(progressBar);
        leftStatus.add(etaLabel);
        statusBar.add(leftStatus, BorderLayout.WEST);

        JButton copyLogButton = new JButton("Copy Log");
        copyLogButton.setToolTipText("Copies the full contents of the log panel to the clipboard.");
        copyLogButton.addActionListener(e -> {
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(log.getText());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            statusLabel.setText("Log copied to clipboard.");
        });
        JButton clearLogButton = new JButton("Clear Log");
        clearLogButton.addActionListener(e -> log.setText(""));
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        cancelButton.setEnabled(false);
        cancelButton.setFont(cancelButton.getFont().deriveFont(Font.BOLD));
        cancelButton.setForeground(new Color(0xff, 0x6b, 0x6b));
        cancelButton.setToolTipText("Interrupts the running job -- it stops at its next safe checkpoint and " +
                "uses/saves whatever partial result it had so far, rather than just hanging.");
        cancelButton.addActionListener(e -> {
            if (currentJob != null) {
                currentJob.cancel(true);
                appendLog("Cancel requested -- stopping at the next safe checkpoint...\n");
            }
        });
        rightButtons.add(copyLogButton);
        rightButtons.add(clearLogButton);
        rightButtons.add(cancelButton);
        statusBar.add(rightButtons, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(logScroll, BorderLayout.CENTER);
        bottom.add(statusBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, bottom);
        split.setResizeWeight(0.55);
        setContentPane(split);

        appendLog("ARC Rocket Simulation Toolkit ready. Pick a tab, fill in the form, and click Run.\n");
    }

    private JPanel buildFullSweepTab() {
        JTextField orkField = new JTextField();
        JTextField configField = new JTextField(new File(lastDir, "sweep_grid.properties").getPath());
        JTextField outDirField = new JTextField();
        JCheckBox forceBox = new JCheckBox("Force run even if over the safety cap");
        JTextField resumeIndexField = new JTextField("0");
        bindPersistentText("engine1.orkFile", orkField);
        bindPersistentText("engine1.configFile", configField);
        bindPersistentText("engine1.outDir", outDirField);

        AxisFields windAvgAxis = new AxisFields("windAvg", 0, 22, 45, 0.5);
        AxisFields windStdDevAxis = new AxisFields("windStdDev", 0, 6, 7, 0.5);
        AxisFields turbulenceAxis = new AxisFields("turbulencePct", 0, 60, 7, 1.0);
        AxisFields windDirAxis = new AxisFields("windDir", 0, 350, 36, 5.0);
        AxisFields tempAxis = new AxisFields("temp", -10, 40, 11, 1.0);
        AxisFields pressureAxis = new AxisFields("pressure", 970, 1030, 7, 1.0);
        AxisFields rodAngleAxis = new AxisFields("rodAngle", 0, 6, 3, 0.5);
        AxisFields[] axes = {windAvgAxis, windStdDevAxis, turbulenceAxis, windDirAxis, tempAxis, pressureAxis, rodAngleAxis};

        MultiSiteSelector siteSelector = new MultiSiteSelector();

        FormBuilder form = new FormBuilder();
        form.addFileRow("Rocket (.ork) file:", orkField, true, "OpenRocket files (*.ork)", "ork");
        form.addRow("Launch sites (swept over all checked):", siteSelector);

        JComboBox<String> presetCombo = new JComboBox<>(new String[]{
                "Quick (~16k combos/site -- fast preview)",
                "Standard (~18.3M combos/site -- default resolution)",
                "Exhaustive (~73M combos/site -- 4x finer wind avg & direction)"
        });
        presetCombo.setSelectedIndex(1);
        form.addRow("Grid preset:", presetCombo);
        form.addRow("", hintLabel("Presets set the ranges/step-counts below -- pick one as a starting point, then " +
                "fine-tune any individual row. Always check Preview before running."));

        presetCombo.addActionListener(e -> {
            int[][] presets = {
                    // {windAvgCount, windStdDevCount, turbulenceCount, windDirCount, tempCount, pressureCount, rodAngleCount}
                    {5, 3, 3, 8, 5, 3, 3},      // Quick
                    {45, 7, 7, 36, 11, 7, 3},   // Standard
                    {90, 7, 7, 72, 11, 7, 3},   // Exhaustive
            };
            int[] p = presets[presetCombo.getSelectedIndex()];
            windAvgAxis.min.setValue(0.0); windAvgAxis.max.setValue(22.0); windAvgAxis.count.setValue(p[0]);
            windStdDevAxis.min.setValue(0.0); windStdDevAxis.max.setValue(6.0); windStdDevAxis.count.setValue(p[1]);
            turbulenceAxis.min.setValue(0.0); turbulenceAxis.max.setValue(60.0); turbulenceAxis.count.setValue(p[2]);
            windDirAxis.min.setValue(0.0); windDirAxis.max.setValue(350.0); windDirAxis.count.setValue(p[3]);
            tempAxis.min.setValue(-10.0); tempAxis.max.setValue(40.0); tempAxis.count.setValue(p[4]);
            pressureAxis.min.setValue(970.0); pressureAxis.max.setValue(1030.0); pressureAxis.count.setValue(p[5]);
            rodAngleAxis.min.setValue(0.0); rodAngleAxis.max.setValue(6.0); rodAngleAxis.count.setValue(p[6]);
            appendLog("Applied \"" + presetCombo.getSelectedItem() + "\" grid preset.\n");
        });

        FormBuilder axisForm = new FormBuilder();
        axisForm.addAxisRow("Wind avg (m/s):", windAvgAxis.min, windAvgAxis.max, windAvgAxis.count);
        axisForm.addAxisRow("Wind std dev (m/s):", windStdDevAxis.min, windStdDevAxis.max, windStdDevAxis.count);
        axisForm.addAxisRow("Turbulence intensity (%):", turbulenceAxis.min, turbulenceAxis.max, turbulenceAxis.count);
        axisForm.addAxisRow("Wind direction (deg):", windDirAxis.min, windDirAxis.max, windDirAxis.count);
        axisForm.addAxisRow("Temperature (C):", tempAxis.min, tempAxis.max, tempAxis.count);
        axisForm.addAxisRow("Pressure (mbar):", pressureAxis.min, pressureAxis.max, pressureAxis.count);
        axisForm.addAxisRow("Launch rod angle (deg):", rodAngleAxis.min, rodAngleAxis.max, rodAngleAxis.count);

        JPanel configRow = form.addFileRow("Grid config (.properties):", configField, true, "Properties files (*.properties)", "properties");
        JButton loadRangesButton = new JButton("Load ranges from file");
        JButton saveRangesButton = new JButton("Save ranges to file");
        JButton editConfigButton = new JButton("Edit sites/safety cap...");
        configRow.add(loadRangesButton);
        configRow.add(saveRangesButton);
        configRow.add(editConfigButton);
        editConfigButton.addActionListener(e -> editPropertiesFile(new File(configField.getText().trim())));

        loadRangesButton.addActionListener(e -> {
            File configFile = requireExistingFile(configField, "grid config .properties file");
            if (configFile == null) return;
            try {
                GridAxis.SweepConfig cfg = GridAxis.load(configFile);
                windAvgAxis.loadFrom(cfg.windAvg);
                windStdDevAxis.loadFrom(cfg.windStdDev);
                turbulenceAxis.loadFrom(cfg.turbulencePct);
                windDirAxis.loadFrom(cfg.windDir);
                tempAxis.loadFrom(cfg.temp);
                pressureAxis.loadFrom(cfg.pressure);
                rodAngleAxis.loadFrom(cfg.rodAngle);
                siteSelector.setSelectedSites(cfg.sites);
                appendLog("Loaded grid ranges and sites from " + configFile.getName() + ".\n");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not load config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        saveRangesButton.addActionListener(e -> {
            File configFile = new File(configField.getText().trim());
            try {
                saveAxisRangesToConfig(configFile, axes, siteSelector.getSelectedSiteSpecs());
                appendLog("Saved grid ranges and sites to " + configFile.getName() + ".\n");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not save config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        form.addRow("", axisForm.panel());
        form.addDirRow("Output folder (blank = \"" + OutputNaming.FULL_FACTORIAL_FOLDER + "\" next to the rocket file):", outDirField);
        form.addRow("", hintLabel("Filename is generated automatically as " +
                "&lt;rocketName&gt;_fullfactorial_&lt;timestamp&gt;.parquet -- never overwrites a previous run. " +
                "Parquet instead of xlsx because a true full-factorial run can produce far more rows than Excel's " +
                "~1,048,576-row-per-sheet limit; open it in the Data Viewer tab, or pandas/DuckDB/Excel-with-a-plugin. " +
                "A companion &lt;...&gt;_summary.csv (success rate + correlations) is written alongside it."));
        form.addRow("", forceBox);
        form.addRow("Resume from index (0 = start from scratch):", resumeIndexField);
        form.addRow("", hintLabel("If a run gets cancelled or fails partway through, this field auto-fills with the " +
                "safe resume point after it stops -- click Run again to pick up where it left off. Resuming writes a " +
                "NEW output file covering just the remainder; you'll have two files to look at together for the full grid."));
        form.addRow("", hintLabel("Check any combination of sites above (Custom uses the lat/lon/alt fields, " +
                "\"Use Current Location\" fills them in for you) -- each checked site is swept as its own axis. " +
                "The safety cap and thread count are still set inside the config file -- click " +
                "\"Edit sites/safety cap...\" to change them. The ranges and sites above always take priority " +
                "over the file's when you run or preview."));

        LeaderboardPanel leaderboardPanel = new LeaderboardPanel(
                "Most favorable conditions seen so far (live, closest to apogee/time target)", "Error score");

        JButton previewButton = new JButton("Preview combination count & time estimate");
        JButton runButton = new JButton("Run Full Factorial Sweep");
        stylePrimaryButton(runButton);
        JButton reportButton = new JButton("Generate PDF Report");
        reportButton.setEnabled(false);
        reportButton.setToolTipText("Enabled after a run completes -- summarizes the run and any conditions meeting both targets.");
        final File[] lastOutputFile = new File[1];

        previewButton.addActionListener(e -> {
            File configFile = requireExistingFile(configField, "grid config .properties file");
            if (configFile == null) return;
            if (siteSelector.getSelectedSites().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Check at least one launch site.", "No sites selected", JOptionPane.WARNING_MESSAGE);
                return;
            }
            runJob("Preview grid size", listener -> {
                GridAxis.SweepConfig cfg = buildSweepConfig(configFile, axes, siteSelector.getSelectedSites());
                long total = cfg.totalCombos();
                long resumeFrom;
                try {
                    resumeFrom = Long.parseLong(resumeIndexField.getText().trim());
                } catch (NumberFormatException nfe) {
                    resumeFrom = 0;
                }
                long remaining = Math.max(0, total - Math.min(resumeFrom, total));
                double estSec = remaining * 0.03;
                System.out.printf("Grid total: %,d combinations%s%n", total,
                        resumeFrom > 0 ? String.format(" (%,d remaining after resume index %,d)", remaining, resumeFrom) : "");
                System.out.printf("Estimated time: ~%.1f hours single-threaded, ~%.1f hours across %d threads%n",
                        estSec / 3600.0, estSec / 3600.0 / cfg.threads, cfg.threads);
                if (remaining > cfg.maxCombosSafety) {
                    System.out.println("This EXCEEDS the safety cap (" + cfg.maxCombosSafety +
                            ") -- you'll need to check 'Force' or coarsen the grid to actually run it.");
                }
            });
        });

        runButton.addActionListener(e -> {
            File ork = requireFile(orkField, "rocket .ork file");
            if (ork == null) return;
            File configFile = requireExistingFile(configField, "grid config .properties file");
            if (configFile == null) return;
            if (siteSelector.getSelectedSites().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Check at least one launch site.", "No sites selected", JOptionPane.WARNING_MESSAGE);
                return;
            }
            File outDir = resolveOutDir(outDirField, ork, OutputNaming.FULL_FACTORIAL_FOLDER);
            boolean force = forceBox.isSelected();
            long resumeFrom;
            try {
                resumeFrom = Long.parseLong(resumeIndexField.getText().trim());
            } catch (NumberFormatException nfe) {
                resumeFrom = 0;
            }

            leaderboardPanel.clear();
            long finalResumeFrom = resumeFrom;
            runJob("Engine 1: Full Factorial Sweep", listener -> {
                GridAxis.SweepConfig cfg = buildSweepConfig(configFile, axes, siteSelector.getSelectedSites());
                File out = FullFactorialSweep.run(ork, cfg, finalResumeFrom, outDir, force, listener, leaderboardPanel::update);
                if (out != null) {
                    openFileLocation(out);
                    lastOutputFile[0] = out;
                    Long safeResume = readSafeResumeIndex(out);
                    SwingUtilities.invokeLater(() -> {
                        reportButton.setEnabled(true);
                        if (safeResume != null) {
                            resumeIndexField.setText(String.valueOf(safeResume));
                            appendLog("Run stopped early -- \"Resume from index\" pre-filled with " + safeResume +
                                    ". Click Run again to continue where this one left off.\n");
                        } else {
                            resumeIndexField.setText("0");
                        }
                    });
                }
            });
        });

        reportButton.addActionListener(e -> {
            if (lastOutputFile[0] == null) return;
            runJob("Engine 1: Generate PDF Report", listener -> {
                File parquet = lastOutputFile[0];
                File reportPdf = new File(parquet.getParentFile(), OutputNaming.baseName(parquet) + "_report.pdf");
                ReportGenerator.generateFullFactorialReport(parquet, reportPdf);
                System.out.println("Wrote " + reportPdf.getAbsolutePath());
                openFileLocation(reportPdf);
            });
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(verticalSplit(form.panel(), leaderboardPanel, 0.35), BorderLayout.CENTER);
        panel.add(buttonRow(previewButton, runButton, reportButton), BorderLayout.SOUTH);
        return withPadding(panel);
    }

    private static Long readSafeResumeIndex(File parquetOutFile) {
        try {
            File summaryFile = new File(parquetOutFile.getParentFile(), OutputNaming.baseName(parquetOutFile) + "_summary.csv");
            if (!summaryFile.exists()) return null;
            CsvUtil.Table table = CsvUtil.read(summaryFile);
            for (List<String> row : table.rows) {
                if (row.size() >= 2 && row.get(0).equals("Safe resume index")) {
                    return Long.parseLong(row.get(1).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static GridAxis.SweepConfig buildSweepConfig(File configFile, AxisFields[] axes, List<LaunchSite> sites) throws Exception {
        GridAxis.SweepConfig cfg = GridAxis.load(configFile);
        cfg.windAvg = axes[0].toGridAxis();
        cfg.windStdDev = axes[1].toGridAxis();
        cfg.turbulencePct = axes[2].toGridAxis();
        cfg.windDir = axes[3].toGridAxis();
        cfg.temp = axes[4].toGridAxis();
        cfg.pressure = axes[5].toGridAxis();
        cfg.rodAngle = axes[6].toGridAxis();
        cfg.sites = sites;
        return cfg;
    }

    private static void saveAxisRangesToConfig(File configFile, AxisFields[] axes, List<String> siteSpecs) throws Exception {
        java.util.Properties p = new java.util.Properties();
        if (configFile.exists()) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(configFile)) {
                p.load(in);
            }
        }
        for (AxisFields a : axes) {
            GridAxis g = a.toGridAxis();
            p.setProperty(a.propKey + ".min", String.valueOf(g.min));
            p.setProperty(a.propKey + ".max", String.valueOf(g.max));
            p.setProperty(a.propKey + ".step", String.valueOf(g.step));
        }
        p.setProperty("sites", String.join(",", siteSpecs));
        p.setProperty("maxCombosSafety", p.getProperty("maxCombosSafety", "50000000"));
        p.setProperty("threads", p.getProperty("threads", String.valueOf(Runtime.getRuntime().availableProcessors())));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(configFile)) {
            p.store(out, "Full-factorial sweep grid for FullFactorialSweep (Engine 1).");
        }
    }

    private JPanel buildDesignTab() {
        JTextField orkField = new JTextField();
        JButton inspectButton = new JButton("Inspect Rocket");

        JComboBox<RocketInspector.Item<MassComponent>> ballastCombo = new JComboBox<>();
        JComboBox<RocketInspector.Item<Parachute>> parachuteCombo = new JComboBox<>();
        JComboBox<RocketInspector.Item<TrapezoidFinSet>> finSetCombo = new JComboBox<>();
        ballastCombo.setEnabled(false);
        parachuteCombo.setEnabled(false);
        finSetCombo.setEnabled(false);

        RocketPreviewPanel previewPanel = new RocketPreviewPanel();
        previewPanel.setPreferredSize(new Dimension(880, 180));
        previewPanel.setBorder(BorderFactory.createTitledBorder("Rocket preview (approximate schematic, not to-scale CAD)"));

        SiteSelector siteSelector = new SiteSelector();
        JSpinner targetApogee = new JSpinner(new SpinnerNumberModel(243.84, 0.0, 100000.0, 1.0));
        JSpinner targetTimeMin = new JSpinner(new SpinnerNumberModel(37.5, 0.0, 600.0, 0.5));
        JSpinner targetTimeMax = new JSpinner(new SpinnerNumberModel(39.5, 0.0, 600.0, 0.5));
        JSpinner windAvg = new JSpinner(new SpinnerNumberModel(3.8, 0.0, 20.0, 0.1));
        JSpinner windStdDev = new JSpinner(new SpinnerNumberModel(0.6, 0.0, 5.0, 0.1));
        JSpinner turbulencePct = new JSpinner(new SpinnerNumberModel(17.0, 0.0, 50.0, 0.5));
        JSpinner windDir = new JSpinner(new SpinnerNumberModel(270.0, 0.0, 360.0, 0.5));
        JSpinner tempC = new JSpinner(new SpinnerNumberModel(7.06, -50.0, 60.0, 0.5));
        JSpinner pressureMbar = new JSpinner(new SpinnerNumberModel(999.76, 800.0, 1100.0, 0.5));

        JSpinner maxBallastKg = new JSpinner(new SpinnerNumberModel(5.0, 0.0, 1000.0, 0.5));
        JSpinner maxFinHeightM = new JSpinner(new SpinnerNumberModel(0.5, 0.01, 10.0, 0.05));
        JSpinner maxHoleRadiusIn = new JSpinner(new SpinnerNumberModel(3.5, 0.0, 4.0, 0.1));
        JSpinner maxSolverPasses = new JSpinner(new SpinnerNumberModel(1000, 1, 100000, 50));
        JTextField outDirField = new JTextField();
        JButton bigRocketButton = new JButton("Big rocket? Use larger bounds");
        bigRocketButton.addActionListener(e -> {
            DesignSolver.Bounds big = DesignSolver.Bounds.big();
            maxBallastKg.setValue(big.maxBallastKg);
            maxFinHeightM.setValue(big.maxFinHeightM);
        });

        bindPersistentText("engine2.orkFile", orkField);
        bindPersistentText("engine2.outDir", outDirField);
        bindPersistentSpinner("engine2.targetApogee", targetApogee);
        bindPersistentSpinner("engine2.targetTimeMin", targetTimeMin);
        bindPersistentSpinner("engine2.targetTimeMax", targetTimeMax);
        bindPersistentSpinner("engine2.windAvg", windAvg);
        bindPersistentSpinner("engine2.windStdDev", windStdDev);
        bindPersistentSpinner("engine2.turbulencePct", turbulencePct);
        bindPersistentSpinner("engine2.windDir", windDir);
        bindPersistentSpinner("engine2.tempC", tempC);
        bindPersistentSpinner("engine2.pressureMbar", pressureMbar);
        bindPersistentSpinner("engine2.maxBallastKg", maxBallastKg);
        bindPersistentSpinner("engine2.maxFinHeightM", maxFinHeightM);
        bindPersistentSpinner("engine2.maxHoleRadiusIn", maxHoleRadiusIn);
        bindPersistentSpinner("engine2.maxSolverPasses", maxSolverPasses);

        final SimRunner[] inspectedRunner = new SimRunner[1];

        inspectButton.addActionListener(e -> {
            File ork = requireFile(orkField, "rocket .ork file");
            if (ork == null) return;
            try {
                SimRunner runner = new SimRunner(ork);
                inspectedRunner[0] = runner;
                info.openrocket.core.rocketcomponent.Rocket rocket = runner.getDocument().getRocket();

                previewPanel.setGeometry(RocketGeometryExtractor.extract(rocket), ork.getName());

                List<RocketInspector.Item<MassComponent>> masses = RocketInspector.listMassComponents(rocket);
                List<RocketInspector.Item<Parachute>> chutes = RocketInspector.listParachutes(rocket);
                List<RocketInspector.Item<TrapezoidFinSet>> fins = RocketInspector.listTrapezoidFinSets(rocket);

                ballastCombo.setModel(new DefaultComboBoxModel<>(masses.toArray(new RocketInspector.Item[0])));
                parachuteCombo.setModel(new DefaultComboBoxModel<>(chutes.toArray(new RocketInspector.Item[0])));
                finSetCombo.setModel(new DefaultComboBoxModel<>(fins.toArray(new RocketInspector.Item[0])));

                selectMatching(ballastCombo, RocketInspector.suggestBallastDefault(rocket));
                selectMatching(parachuteCombo, RocketInspector.suggestMainParachuteDefault(chutes));
                selectMatching(finSetCombo, RocketInspector.suggestFinSetDefault(fins));

                ballastCombo.setEnabled(!masses.isEmpty());
                parachuteCombo.setEnabled(!chutes.isEmpty());
                finSetCombo.setEnabled(!fins.isEmpty());

                appendLog(String.format("Inspected %s: found %d mass component(s), %d parachute(s), %d trapezoidal fin set(s). " +
                        "Defaults pre-selected -- override any of them below if the guess is wrong.%n",
                        ork.getName(), masses.size(), chutes.size(), fins.size()));
                if (fins.isEmpty()) {
                    appendLog("WARNING: no trapezoidal fin sets found -- Engine 2 needs one to drive fin height/sweep. " +
                            "If your fins are a different shape (freeform/elliptical), this engine can't solve fin geometry for this rocket.\n");
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not inspect rocket: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        FormBuilder rocketForm = new FormBuilder();
        rocketForm.addFileRow("Rocket (.ork) file:", orkField, true, "OpenRocket files (*.ork)", "ork");
        rocketForm.addRow("", inspectButton);
        rocketForm.addRow("Ballast component:", ballastCombo);
        rocketForm.addRow("Parachute (held fixed):", parachuteCombo);
        rocketForm.addRow("Fin set to solve:", finSetCombo);

        FormBuilder targetForm = new FormBuilder();
        targetForm.addRow("Target apogee (m):", targetApogee);
        targetForm.addRow("Target flight time min (s):", targetTimeMin);
        targetForm.addRow("Target flight time max (s):", targetTimeMax);
        targetForm.addRow("Launch site:", siteSelector);

        FormBuilder envForm = new FormBuilder();
        envForm.addRow("Wind average (m/s):", windAvg);
        envForm.addRow("Wind std dev (m/s):", windStdDev);
        envForm.addRow("Turbulence intensity (%):", turbulencePct);
        envForm.addRow("Wind direction (deg):", windDir);
        envForm.addRow("Temperature (C):", tempC);
        envForm.addRow("Pressure (mbar):", pressureMbar);

        FormBuilder boundsForm = new FormBuilder();
        boundsForm.addRow("Max ballast (kg):", maxBallastKg);
        boundsForm.addRow("Max fin height (m):", maxFinHeightM);
        boundsForm.addRow("Max parachute center hole radius (in, 4 in = 8 in diameter max):", maxHoleRadiusIn);
        boundsForm.addRow("Max solver passes (ballast+fin+hole rounds):", maxSolverPasses);
        boundsForm.addRow("", bigRocketButton);
        boundsForm.addDirRow("Output folder (blank = \"" + OutputNaming.OPENROCKET_SOLVES_FOLDER + "\" next to the rocket file):", outDirField);

        Box groupedForm = Box.createVerticalBox();
        groupedForm.add(titledGroup("Rocket & components", rocketForm.panel()));
        groupedForm.add(titledGroup("Targets & launch site", targetForm.panel()));
        groupedForm.add(titledGroup("Fixed environment (single condition)", envForm.panel()));
        groupedForm.add(titledGroup("Search bounds", boundsForm.panel()));

        LeaderboardPanel leaderboardPanel = new LeaderboardPanel(
                "Closest simulation to target seen so far (live)", "Error score");

        JButton runButton = new JButton("Solve Ballast + Fin Height + Parachute Hole");
        runButton.addActionListener(e -> {
            if (inspectedRunner[0] == null) {
                JOptionPane.showMessageDialog(this, "Click 'Inspect Rocket' first so the solver knows which " +
                        "ballast/parachute/fin set to use.", "Not inspected yet", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LaunchSite site = siteSelector.getSelectedSite();

            DesignSolver.ComponentSelection selection = new DesignSolver.ComponentSelection();
            RocketInspector.Item<MassComponent> ballastItem = (RocketInspector.Item<MassComponent>) ballastCombo.getSelectedItem();
            RocketInspector.Item<Parachute> chuteItem = (RocketInspector.Item<Parachute>) parachuteCombo.getSelectedItem();
            RocketInspector.Item<TrapezoidFinSet> finItem = (RocketInspector.Item<TrapezoidFinSet>) finSetCombo.getSelectedItem();
            if (ballastItem != null) selection.ballastComponents = List.of(ballastItem.component);
            if (chuteItem != null) selection.parachute = chuteItem.component;
            if (finItem != null) selection.finSet = finItem.component;

            DesignSolver.Bounds bounds = new DesignSolver.Bounds();
            bounds.maxBallastKg = (Double) maxBallastKg.getValue();
            bounds.maxFinHeightM = (Double) maxFinHeightM.getValue();
            bounds.maxHoleRadiusM = (Double) maxHoleRadiusIn.getValue() * 0.0254;
            bounds.maxOuterIters = (Integer) maxSolverPasses.getValue();

            SimRunner runner = inspectedRunner[0];
            File ork = new File(orkField.getText().trim());
            File outDir = resolveOutDir(outDirField, ork, OutputNaming.OPENROCKET_SOLVES_FOLDER);

            leaderboardPanel.clear();
            runJob("Engine 2: Design Solver", listener -> DesignSolver.run(
                    runner, ork,
                    (Double) targetApogee.getValue(),
                    (Double) targetTimeMin.getValue(),
                    (Double) targetTimeMax.getValue(),
                    site,
                    (Double) windAvg.getValue(),
                    (Double) windStdDev.getValue(),
                    (Double) turbulencePct.getValue(),
                    (Double) windDir.getValue(),
                    (Double) tempC.getValue(),
                    (Double) pressureMbar.getValue(),
                    selection, bounds, outDir, listener, leaderboardPanel::update
            ));
        });

        stylePrimaryButton(runButton);

        JPanel top = new JPanel(new BorderLayout());
        top.add(previewPanel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(groupedForm);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        top.add(scroll, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(verticalSplit(top, leaderboardPanel, 0.6), BorderLayout.CENTER);
        panel.add(buttonRow(runButton), BorderLayout.SOUTH);
        return withPadding(panel);
    }

    private JPanel buildGeometryExportTab() {
        JTextField orkField = new JTextField();
        JButton loadButton = new JButton("Load Rocket");
        RocketPreviewPanel previewPanel = new RocketPreviewPanel();
        previewPanel.setPreferredSize(new Dimension(880, 180));
        previewPanel.setBorder(BorderFactory.createTitledBorder("Rocket preview (approximate schematic, not to-scale CAD)"));

        JCheckBox stlBox = new JCheckBox("STL", true);
        JCheckBox objBox = new JCheckBox("OBJ", true);
        JTextField outDirField = new JTextField();
        bindPersistentText("engine3.orkFile", orkField);
        bindPersistentText("engine3.outDir", outDirField);

        final RocketGeometryExtractor.Geometry[] loadedGeometry = new RocketGeometryExtractor.Geometry[1];
        final File[] loadedOrk = new File[1];

        loadButton.addActionListener(e -> {
            File ork = requireFile(orkField, "rocket .ork file");
            if (ork == null) return;
            try {
                SimRunner runner = new SimRunner(ork);
                info.openrocket.core.rocketcomponent.Rocket rocket = runner.getDocument().getRocket();
                RocketGeometryExtractor.Geometry geo = RocketGeometryExtractor.extract(rocket);
                previewPanel.setGeometry(geo, ork.getName());
                loadedGeometry[0] = geo;
                loadedOrk[0] = ork;
                appendLog(String.format("Loaded %s: %d body section(s), %d fin set(s), total length %.3f m.%n",
                        ork.getName(), geo.bodies.size(), geo.fins.size(), geo.totalLength));
                if (!geo.skipped.isEmpty()) {
                    appendLog("Skipped (not renderable as external geometry): " + geo.skipped + "\n");
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not load rocket: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        FormBuilder form = new FormBuilder();
        form.addFileRow("Rocket (.ork) file:", orkField, true, "OpenRocket files (*.ork)", "ork");
        form.addRow("", loadButton);
        JPanel formatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        formatRow.add(stlBox);
        formatRow.add(objBox);
        form.addRow("Export format(s):", formatRow);
        form.addDirRow("Output folder (blank = \"" + OutputNaming.CAD_FILES_FOLDER + "\" next to the rocket file):", outDirField);
        form.addRow("", hintLabel("Basic body-of-revolution + flat-fin mesh (not CAD-fidelity) -- good for a " +
                "quick 3D-print / CAD-import sanity check of the outer mold line, not a substitute for real CAD " +
                "geometry. No wall thickness, internal components, or airfoil fin sections. Units: millimeters. " +
                "Each run gets its own new subfolder (&lt;rocketName&gt;_geometry_&lt;timestamp&gt;/) inside the " +
                "output folder above, so nothing is ever overwritten and every export's files stay together."));

        JButton exportButton = new JButton("Export Mesh");
        stylePrimaryButton(exportButton);
        exportButton.addActionListener(e -> {
            if (loadedGeometry[0] == null) {
                JOptionPane.showMessageDialog(this, "Click 'Load Rocket' first.", "Not loaded yet", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!stlBox.isSelected() && !objBox.isSelected()) {
                JOptionPane.showMessageDialog(this, "Pick at least one export format.", "Nothing to export", JOptionPane.WARNING_MESSAGE);
                return;
            }
            RocketGeometryExtractor.Geometry geo = loadedGeometry[0];
            File ork = loadedOrk[0];
            File outDir = resolveOutDir(outDirField, ork, OutputNaming.CAD_FILES_FOLDER);
            boolean doStl = stlBox.isSelected(), doObj = objBox.isSelected();

            runJob("Engine 3: Geometry Export", listener -> {

                File runDir = OutputNaming.uniqueDir(ork, outDir, "geometry");
                String base = OutputNaming.baseName(ork);
                List<MeshExporter.Triangle> tris = MeshExporter.buildMesh(geo);
                System.out.println("Built mesh: " + tris.size() + " triangles. Writing to " + runDir.getAbsolutePath());
                File written = null;
                if (doStl) {
                    File stlOut = new File(runDir, base + ".stl");
                    MeshExporter.writeStl(tris, stlOut, ork.getName());
                    System.out.println("Wrote " + stlOut.getAbsolutePath());
                    written = stlOut;
                }
                if (doObj) {
                    File objOut = new File(runDir, base + ".obj");
                    MeshExporter.writeObj(tris, objOut, ork.getName());
                    System.out.println("Wrote " + objOut.getAbsolutePath());
                    written = objOut;
                }
                if (written != null) openDirectory(runDir);
            });
        });

        JScrollPane scroll = new JScrollPane(form.panel());
        scroll.setBorder(null);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(verticalSplit(previewPanel, scroll, 0.4), BorderLayout.CENTER);
        panel.add(buttonRow(exportButton), BorderLayout.SOUTH);
        return withPadding(panel);
    }

    private JPanel buildWeatherTab() {
        WeatherClient weatherClient = new WeatherClient(weatherApiKey());
        SiteSelector weatherSiteSelector = new SiteSelector();

        JLabel weatherStatusLabel = new JLabel("Not fetched yet.");
        weatherStatusLabel.setForeground(Color.GRAY);
        JSpinner windAvgSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100.0, 0.1));
        JSpinner windGustSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 150.0, 0.1));
        JSpinner windStdDevSpinner = new JSpinner(new SpinnerNumberModel(0.5, 0.0, 20.0, 0.1));
        JSpinner turbulencePctSpinner = new JSpinner(new SpinnerNumberModel(17.0, 0.0, 50.0, 0.5));
        JSpinner windDirSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 360.0, 0.5));
        JSpinner tempSpinner = new JSpinner(new SpinnerNumberModel(15.0, -50.0, 60.0, 0.5));
        JSpinner pressureSpinner = new JSpinner(new SpinnerNumberModel(1013.25, 800.0, 1100.0, 0.5));

        JButton fetchButton = new JButton("Fetch Weather Now");

        java.util.function.Consumer<WeatherClient.Reading> applyReading = r -> {
            windAvgSpinner.setValue(r.windAvgMs);
            windGustSpinner.setValue(r.windGustMs);
            windStdDevSpinner.setValue(r.estimatedWindStdDevMs());
            windDirSpinner.setValue(r.windDirDeg);
            tempSpinner.setValue(r.tempC);
            pressureSpinner.setValue(r.pressureMbar);
            weatherStatusLabel.setText(String.format("%s -- \"%s\" -- fetched %s",
                    r.locationName, r.conditionText, r.formattedFetchTime()));
            appendLog(String.format("Weather pulled for %s: wind %.2f m/s (gust %.2f m/s), %.0f deg, " +
                            "%.1f C, %.1f mbar -- \"%s\" (fetched %s)%n",
                    r.locationName, r.windAvgMs, r.windGustMs, r.windDirDeg, r.tempC, r.pressureMbar,
                    r.conditionText, r.formattedFetchTime()));
        };

        Runnable[] doFetch = new Runnable[1];
        doFetch[0] = () -> {
            if (weatherApiKey() == null || weatherApiKey().isBlank()) {
                JOptionPane.showMessageDialog(this,
                        "No weather API key is configured yet. Set one under File > Preferences " +
                                "(a free key is available at weatherapi.com) to use live weather pulls.",
                        "Weather API key required", JOptionPane.WARNING_MESSAGE);
                return;
            }
            weatherClient.setApiKey(weatherApiKey());
            weatherStatusLabel.setText("Fetching...");
            fetchButton.setEnabled(false);
            LaunchSite site = weatherSiteSelector.getSelectedSite();
            Thread t = new Thread(() -> {
                try {
                    WeatherClient.Reading r = weatherClient.getCurrent(site.latitudeDeg, site.longitudeDeg);
                    SwingUtilities.invokeLater(() -> {
                        applyReading.accept(r);
                        long cooldownMin = weatherClient.msUntilNextAllowedFetch() / 60000;
                        weatherStatusLabel.setText(weatherStatusLabel.getText() +
                                String.format(" (next auto-refresh available in ~%d min)", cooldownMin));
                        fetchButton.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        weatherStatusLabel.setText("Fetch failed: " + ex.getMessage());
                        fetchButton.setEnabled(true);
                    });
                }
            }, "weather-fetch");
            t.setDaemon(true);
            t.start();
        };
        fetchButton.addActionListener(e -> doFetch[0].run());

        SwingUtilities.invokeLater(() -> doFetch[0].run());

        JTextField forecastDateField = new JTextField(java.time.LocalDate.now().plusDays(1).toString());
        JSpinner forecastHourSpinner = new JSpinner(new SpinnerNumberModel(12, 0, 23, 1));
        JButton fetchForecastButton = new JButton("Fetch Forecast");
        JPanel forecastRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        forecastRow.add(new JLabel("Date (yyyy-mm-dd):"));
        forecastRow.add(forecastDateField);
        forecastRow.add(new JLabel("Hour (0-23, local time):"));
        forecastRow.add(forecastHourSpinner);
        forecastRow.add(fetchForecastButton);
        fetchForecastButton.addActionListener(e -> {
            if (weatherApiKey() == null || weatherApiKey().isBlank()) {
                JOptionPane.showMessageDialog(this,
                        "No weather API key is configured yet. Set one under File > Preferences.",
                        "Weather API key required", JOptionPane.WARNING_MESSAGE);
                return;
            }
            java.time.LocalDate date;
            try {
                date = java.time.LocalDate.parse(forecastDateField.getText().trim());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Date must be in yyyy-mm-dd format.", "Bad date", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int hour = (Integer) forecastHourSpinner.getValue();
            weatherClient.setApiKey(weatherApiKey());
            weatherStatusLabel.setText("Fetching forecast...");
            fetchForecastButton.setEnabled(false);
            LaunchSite site = weatherSiteSelector.getSelectedSite();
            Thread t = new Thread(() -> {
                try {
                    WeatherClient.Reading r = weatherClient.getForecast(site.latitudeDeg, site.longitudeDeg, date, hour);
                    SwingUtilities.invokeLater(() -> {
                        applyReading.accept(r);
                        fetchForecastButton.setEnabled(true);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        weatherStatusLabel.setText("Forecast fetch failed: " + ex.getMessage());
                        fetchForecastButton.setEnabled(true);
                    });
                }
            }, "weather-forecast-fetch");
            t.setDaemon(true);
            t.start();
        });

        FormBuilder weatherForm = new FormBuilder();
        weatherForm.addRow("Launch site (weather pulled for this location):", weatherSiteSelector);
        weatherForm.addRow("", fetchButton);
        weatherForm.addRow("Or plan for a future launch time:", forecastRow);
        weatherForm.addRow("", hintLabel("Forecasts are limited by your weatherapi.com plan's window (a few days " +
                "ahead on the free tier) -- requesting further out returns an error. Fetching either current or " +
                "forecast weather fills in the same fields below, which you can still hand-edit afterward."));
        weatherForm.addRow("", weatherStatusLabel);
        weatherForm.addRow("Wind average (m/s, from API -- editable):", windAvgSpinner);
        weatherForm.addRow("Wind gust (m/s, from API -- editable):", windGustSpinner);
        weatherForm.addRow("Wind std dev (m/s, ESTIMATED from gust -- override if you know better):", windStdDevSpinner);
        weatherForm.addRow("Turbulence intensity (%, NOT from API -- typical default, override as needed):", turbulencePctSpinner);
        weatherForm.addRow("Wind direction (deg, from API -- editable):", windDirSpinner);
        weatherForm.addRow("Temperature (C, from API -- editable):", tempSpinner);
        weatherForm.addRow("Pressure (mbar, from API -- editable):", pressureSpinner);
        weatherForm.addRow("", hintLabel("Wind std dev isn't reported by the weather API -- it's estimated from the " +
                "gust value ((gust - avg) / 2.5, a rough turbulence rule of thumb), pre-filled but editable. " +
                "Turbulence intensity isn't reported either and defaults to 17% -- override both if you have better " +
                "local knowledge (a nearby anemometer log, prior field experience, etc)."));

        JTextField orkField = new JTextField();
        JButton inspectButton = new JButton("Inspect Rocket");
        JComboBox<RocketInspector.Item<MassComponent>> ballastCombo = new JComboBox<>();
        JComboBox<RocketInspector.Item<Parachute>> parachuteCombo = new JComboBox<>();
        JComboBox<RocketInspector.Item<TrapezoidFinSet>> finSetCombo = new JComboBox<>();
        ballastCombo.setEnabled(false);
        parachuteCombo.setEnabled(false);
        finSetCombo.setEnabled(false);

        RocketPreviewPanel previewPanel = new RocketPreviewPanel();
        previewPanel.setPreferredSize(new Dimension(880, 160));
        previewPanel.setBorder(BorderFactory.createTitledBorder("Rocket preview (approximate schematic, not to-scale CAD)"));

        final SimRunner[] inspectedRunner = new SimRunner[1];

        inspectButton.addActionListener(e -> {
            File ork = requireFile(orkField, "rocket .ork file");
            if (ork == null) return;
            try {
                SimRunner runner = new SimRunner(ork);
                inspectedRunner[0] = runner;
                info.openrocket.core.rocketcomponent.Rocket rocket = runner.getDocument().getRocket();

                previewPanel.setGeometry(RocketGeometryExtractor.extract(rocket), ork.getName());

                List<RocketInspector.Item<MassComponent>> masses = RocketInspector.listMassComponents(rocket);
                List<RocketInspector.Item<Parachute>> chutes = RocketInspector.listParachutes(rocket);
                List<RocketInspector.Item<TrapezoidFinSet>> fins = RocketInspector.listTrapezoidFinSets(rocket);

                ballastCombo.setModel(new DefaultComboBoxModel<>(masses.toArray(new RocketInspector.Item[0])));
                parachuteCombo.setModel(new DefaultComboBoxModel<>(chutes.toArray(new RocketInspector.Item[0])));
                finSetCombo.setModel(new DefaultComboBoxModel<>(fins.toArray(new RocketInspector.Item[0])));

                selectMatching(ballastCombo, RocketInspector.suggestBallastDefault(rocket));
                selectMatching(parachuteCombo, RocketInspector.suggestMainParachuteDefault(chutes));
                selectMatching(finSetCombo, RocketInspector.suggestFinSetDefault(fins));

                ballastCombo.setEnabled(!masses.isEmpty());
                parachuteCombo.setEnabled(!chutes.isEmpty());
                finSetCombo.setEnabled(!fins.isEmpty());

                appendLog(String.format("Inspected %s: found %d mass component(s), %d parachute(s), %d trapezoidal fin set(s).%n",
                        ork.getName(), masses.size(), chutes.size(), fins.size()));
                if (fins.isEmpty()) {
                    appendLog("WARNING: no trapezoidal fin sets found -- Engine 4 needs one, same as Engine 2.\n");
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not inspect rocket: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        FormBuilder rocketForm = new FormBuilder();
        rocketForm.addFileRow("Rocket (.ork) file:", orkField, true, "OpenRocket files (*.ork)", "ork");
        rocketForm.addRow("", inspectButton);
        rocketForm.addRow("Ballast component:", ballastCombo);
        rocketForm.addRow("Parachute (held fixed):", parachuteCombo);
        rocketForm.addRow("Fin set to solve:", finSetCombo);

        JSpinner targetApogee = new JSpinner(new SpinnerNumberModel(243.84, 0.0, 100000.0, 1.0));
        JSpinner targetTimeMin = new JSpinner(new SpinnerNumberModel(37.5, 0.0, 600.0, 0.5));
        JSpinner targetTimeMax = new JSpinner(new SpinnerNumberModel(39.5, 0.0, 600.0, 0.5));

        FormBuilder targetForm = new FormBuilder();
        targetForm.addRow("Target apogee (m):", targetApogee);
        targetForm.addRow("Target flight time min (s):", targetTimeMin);
        targetForm.addRow("Target flight time max (s):", targetTimeMax);

        JSpinner maxBallastKg = new JSpinner(new SpinnerNumberModel(5.0, 0.0, 1000.0, 0.5));
        JSpinner maxFinHeightM = new JSpinner(new SpinnerNumberModel(0.5, 0.01, 10.0, 0.05));
        JSpinner maxHoleRadiusIn = new JSpinner(new SpinnerNumberModel(3.5, 0.0, 4.0, 0.1));
        JSpinner maxSolverPasses = new JSpinner(new SpinnerNumberModel(1000, 1, 100000, 50));
        JSpinner localSweepSamples = new JSpinner(new SpinnerNumberModel(1000, 50, 200000, 100));
        JButton bigRocketButton = new JButton("Big rocket? Use larger bounds");
        bigRocketButton.addActionListener(e -> {
            DesignSolver.Bounds big = DesignSolver.Bounds.big();
            maxBallastKg.setValue(big.maxBallastKg);
            maxFinHeightM.setValue(big.maxFinHeightM);
        });

        bindPersistentText("engine4.orkFile", orkField);
        bindPersistentSpinner("engine4.targetApogee", targetApogee);
        bindPersistentSpinner("engine4.targetTimeMin", targetTimeMin);
        bindPersistentSpinner("engine4.targetTimeMax", targetTimeMax);
        bindPersistentSpinner("engine4.maxBallastKg", maxBallastKg);
        bindPersistentSpinner("engine4.maxFinHeightM", maxFinHeightM);
        bindPersistentSpinner("engine4.maxHoleRadiusIn", maxHoleRadiusIn);
        bindPersistentSpinner("engine4.maxSolverPasses", maxSolverPasses);
        bindPersistentSpinner("engine4.localSweepSamples", localSweepSamples);

        FormBuilder boundsForm = new FormBuilder();
        boundsForm.addRow("Max ballast (kg):", maxBallastKg);
        boundsForm.addRow("Max fin height (m):", maxFinHeightM);
        boundsForm.addRow("Max parachute center hole radius (in, 4 in = 8 in diameter max):", maxHoleRadiusIn);
        boundsForm.addRow("Max solver passes (ballast+fin+hole rounds):", maxSolverPasses);
        boundsForm.addRow("Local-conditions sweep sample count:", localSweepSamples);
        boundsForm.addRow("", bigRocketButton);
        boundsForm.addRow("", hintLabel("The local-conditions sweep re-uses the ALREADY-SOLVED design (fixed ballast/" +
                "fin height/hole radius) across a narrow, realistic day-of envelope centered on the pulled weather -- " +
                "not Engine 1's wide worst-case envelope. Margin fin sets always re-solve fin height only at +/-0.5 " +
                "and +/-1.0 wind-speed std deviations around the pulled average, holding ballast and hole radius fixed."));

        JTextField outDirField = new JTextField();
        bindPersistentText("engine4.outDir", outDirField);
        FormBuilder outputForm = new FormBuilder();
        outputForm.addDirRow("Output folder (blank = \"" + OutputNaming.ENGINE_4_FOLDER + "\" next to the rocket file):", outDirField);
        outputForm.addRow("", hintLabel("Each run gets its own new subfolder (&lt;rocketName&gt;_weatherdesign_&lt;timestamp&gt;/) " +
                "inside the output folder above -- the solved .ork, both CAD exports, the local sweep .xlsx, and all " +
                "four margin fin CAD pairs land together in that one run subfolder."));

        Box groupedForm = Box.createVerticalBox();
        groupedForm.add(titledGroup("Live weather source", weatherForm.panel()));
        groupedForm.add(titledGroup("Rocket & components", rocketForm.panel()));
        groupedForm.add(titledGroup("Targets", targetForm.panel()));
        groupedForm.add(titledGroup("Search bounds & local sweep settings", boundsForm.panel()));
        groupedForm.add(titledGroup("Output", outputForm.panel()));

        LeaderboardPanel mainLeaderboard = new LeaderboardPanel(
                "Closest simulation to target seen so far -- main solve (live)", "Error score");
        LeaderboardPanel localLeaderboard = new LeaderboardPanel(
                "Most favorable local conditions seen so far -- day-of variability check (live)", "Error score");
        JPanel leaderboards = new JPanel(new GridLayout(2, 1, 0, 6));
        leaderboards.add(mainLeaderboard);
        leaderboards.add(localLeaderboard);

        JLabel engine4EtaLabel = new JLabel(" ");
        engine4EtaLabel.setFont(engine4EtaLabel.getFont().deriveFont(Font.BOLD));

        JButton runButton = new JButton("Run Weather-Driven Design (Solve + CAD + Sweep + Margin Fins)");
        stylePrimaryButton(runButton);
        JButton reportButton = new JButton("Generate PDF Report");
        reportButton.setEnabled(false);
        reportButton.setToolTipText("Enabled after a run completes -- summarizes the solved design, weather used, and margin fin sets.");
        final WeatherDrivenDesign.Result[] lastResult = new WeatherDrivenDesign.Result[1];
        final WeatherClient.Reading[] lastWeather = new WeatherClient.Reading[1];
        final double[] lastTargets = new double[3];
        runButton.addActionListener(e -> {
            if (inspectedRunner[0] == null) {
                JOptionPane.showMessageDialog(this, "Click 'Inspect Rocket' first so the solver knows which " +
                        "ballast/parachute/fin set to use.", "Not inspected yet", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!weatherClient.hasCached()) {
                JOptionPane.showMessageDialog(this, "No weather data yet -- wait for the fetch to finish (see status above).",
                        "Weather not ready", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LaunchSite site = weatherSiteSelector.getSelectedSite();

            DesignSolver.ComponentSelection selection = new DesignSolver.ComponentSelection();
            RocketInspector.Item<MassComponent> ballastItem = (RocketInspector.Item<MassComponent>) ballastCombo.getSelectedItem();
            RocketInspector.Item<Parachute> chuteItem = (RocketInspector.Item<Parachute>) parachuteCombo.getSelectedItem();
            RocketInspector.Item<TrapezoidFinSet> finItem = (RocketInspector.Item<TrapezoidFinSet>) finSetCombo.getSelectedItem();
            if (ballastItem != null) selection.ballastComponents = List.of(ballastItem.component);
            if (chuteItem != null) selection.parachute = chuteItem.component;
            if (finItem != null) selection.finSet = finItem.component;

            DesignSolver.Bounds bounds = new DesignSolver.Bounds();
            bounds.maxBallastKg = (Double) maxBallastKg.getValue();
            bounds.maxFinHeightM = (Double) maxFinHeightM.getValue();
            bounds.maxHoleRadiusM = (Double) maxHoleRadiusIn.getValue() * 0.0254;
            bounds.maxOuterIters = (Integer) maxSolverPasses.getValue();

            SimRunner runner = inspectedRunner[0];
            File ork = new File(orkField.getText().trim());
            File outDir = resolveOutDir(outDirField, ork, OutputNaming.ENGINE_4_FOLDER);
            int sweepSamples = (Integer) localSweepSamples.getValue();

            WeatherClient.Reading base = weatherClient.cachedReading();
            WeatherClient.Reading effective = new WeatherClient.Reading(
                    base.locationName, (Double) windAvgSpinner.getValue(), (Double) windGustSpinner.getValue(),
                    (Double) windDirSpinner.getValue(), (Double) tempSpinner.getValue(), (Double) pressureSpinner.getValue(),
                    base.conditionText, base.fetchedAt);
            double windStdDevMs = (Double) windStdDevSpinner.getValue();
            double turbulencePct = (Double) turbulencePctSpinner.getValue();

            mainLeaderboard.clear();
            localLeaderboard.clear();
            engine4EtaLabel.setText("Starting...");
            runJob("Engine 4: Weather-Driven Design", listener -> {
                ProgressListener combined = (processed, total, etaSeconds) -> {
                    listener.onProgress(processed, total, etaSeconds);
                    SwingUtilities.invokeLater(() -> {
                        if (total > 0) {
                            engine4EtaLabel.setText(String.format("Progress: %,d / %,d -- ETA %s",
                                    processed, total, Double.isNaN(etaSeconds) ? "--" : EtaTracker.formatDuration(etaSeconds)));
                        }
                    });
                };
                try {
                    WeatherDrivenDesign.Result result = WeatherDrivenDesign.run(
                            runner, ork, effective, windStdDevMs, turbulencePct,
                            (Double) targetApogee.getValue(), (Double) targetTimeMin.getValue(), (Double) targetTimeMax.getValue(),
                            site, selection, bounds, sweepSamples, outDir, combined, mainLeaderboard::update, localLeaderboard::update
                    );
                    if (result != null && result.runDir != null) openDirectory(result.runDir);
                    if (result != null) {
                        lastResult[0] = result;
                        lastWeather[0] = effective;
                        lastTargets[0] = (Double) targetApogee.getValue();
                        lastTargets[1] = (Double) targetTimeMin.getValue();
                        lastTargets[2] = (Double) targetTimeMax.getValue();
                        SwingUtilities.invokeLater(() -> reportButton.setEnabled(true));
                    }
                } finally {
                    SwingUtilities.invokeLater(() -> engine4EtaLabel.setText("Done."));
                }
            });
        });

        reportButton.addActionListener(e -> {
            if (lastResult[0] == null) return;
            runJob("Engine 4: Generate PDF Report", listener -> {
                File runDir = lastResult[0].runDir != null ? lastResult[0].runDir : AppConfig.appDir();
                File reportPdf = new File(runDir, "weather_design_report.pdf");
                ReportGenerator.generateWeatherDesignReport(lastResult[0], lastWeather[0],
                        lastTargets[0], lastTargets[1], lastTargets[2], reportPdf);
                System.out.println("Wrote " + reportPdf.getAbsolutePath());
                openFileLocation(reportPdf);
            });
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(previewPanel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(groupedForm);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        top.add(scroll, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(verticalSplit(top, leaderboards, 0.6), BorderLayout.CENTER);
        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.add(engine4EtaLabel, BorderLayout.NORTH);
        bottomRow.add(buttonRow(runButton, reportButton), BorderLayout.SOUTH);
        panel.add(bottomRow, BorderLayout.SOUTH);
        return withPadding(panel);
    }

    @SuppressWarnings("unchecked")
    private static <T> void selectMatching(JComboBox<RocketInspector.Item<T>> combo, T target) {
        if (target == null) return;
        ComboBoxModel<RocketInspector.Item<T>> model = combo.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).component == target) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private class SiteSelector extends JPanel {
        private final JComboBox<String> combo;
        private final JSpinner latSpinner;
        private final JSpinner lonSpinner;
        private final JSpinner altSpinner;
        private final JButton locateButton;

        SiteSelector() {
            super(new FlowLayout(FlowLayout.LEFT, 6, 0));
            combo = new JComboBox<>(new String[]{
                    LaunchSite.MDRA_SOD_FARM.label, LaunchSite.SPAAR_LANCASTER.label, "Custom..."
            });
            latSpinner = new JSpinner(new SpinnerNumberModel(39.0, -90.0, 90.0, 0.0001));
            lonSpinner = new JSpinner(new SpinnerNumberModel(-76.1, -180.0, 180.0, 0.0001));
            altSpinner = new JSpinner(new SpinnerNumberModel(100.0, -500.0, 10000.0, 1.0));
            ((JSpinner.NumberEditor) latSpinner.getEditor()).getFormat().setMaximumFractionDigits(5);
            ((JSpinner.NumberEditor) lonSpinner.getEditor()).getFormat().setMaximumFractionDigits(5);
            latSpinner.setPreferredSize(new Dimension(90, latSpinner.getPreferredSize().height));
            lonSpinner.setPreferredSize(new Dimension(90, lonSpinner.getPreferredSize().height));
            altSpinner.setPreferredSize(new Dimension(70, altSpinner.getPreferredSize().height));
            locateButton = new JButton("Use Current Location");
            locateButton.setToolTipText("Resolve this machine's current position (on-device fix if available, "
                    + "otherwise an IP-geolocation approximation) and fill it in as a Custom site.");

            setCustomFieldsEnabled(false);
            syncSpinnersToSelection();
            combo.addActionListener(e -> {
                setCustomFieldsEnabled(combo.getSelectedIndex() == 2);
                syncSpinnersToSelection();
            });
            locateButton.addActionListener(e -> onLocateCurrentPosition());

            add(combo);
            add(new JLabel("lat:"));
            add(latSpinner);
            add(new JLabel("lon:"));
            add(lonSpinner);
            add(new JLabel("alt(m):"));
            add(altSpinner);
            add(locateButton);
        }

        private void onLocateCurrentPosition() {
            locateButton.setEnabled(false);
            locateButton.setText("Locating...");
            new SwingWorker<DeviceLocation.Reading, Void>() {
                @Override
                protected DeviceLocation.Reading doInBackground() throws Exception {
                    return DeviceLocation.fetch(weatherApiKey());
                }

                @Override
                protected void done() {
                    locateButton.setEnabled(true);
                    locateButton.setText("Use Current Location");
                    try {
                        DeviceLocation.Reading r = get();
                        combo.setSelectedIndex(2);
                        latSpinner.setValue(r.latitudeDeg);
                        lonSpinner.setValue(r.longitudeDeg);
                        altSpinner.setValue(r.altitudeM);
                        System.out.printf("Current location resolved via %s: %.5f, %.5f, %.0f m%n",
                                r.source, r.latitudeDeg, r.longitudeDeg, r.altitudeM);
                        System.out.println(r.accuracyNote);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        System.err.println("Could not resolve current location: " + cause.getMessage());
                        JOptionPane.showMessageDialog(SiteSelector.this,
                                "Could not resolve current location:\n" + cause.getMessage(),
                                "Location unavailable", JOptionPane.WARNING_MESSAGE);
                    }
                }
            }.execute();
        }

        private void setCustomFieldsEnabled(boolean enabled) {
            latSpinner.setEnabled(enabled);
            lonSpinner.setEnabled(enabled);
            altSpinner.setEnabled(enabled);
        }

        private void syncSpinnersToSelection() {
            LaunchSite preset = switch (combo.getSelectedIndex()) {
                case 0 -> LaunchSite.MDRA_SOD_FARM;
                case 1 -> LaunchSite.SPAAR_LANCASTER;
                default -> null;
            };
            if (preset != null) {
                latSpinner.setValue(preset.latitudeDeg);
                lonSpinner.setValue(preset.longitudeDeg);
                altSpinner.setValue(preset.altitudeM);
            }
        }

        LaunchSite getSelectedSite() {
            switch (combo.getSelectedIndex()) {
                case 0: return LaunchSite.MDRA_SOD_FARM;
                case 1: return LaunchSite.SPAAR_LANCASTER;
                default: return LaunchSite.custom((Double) latSpinner.getValue(), (Double) lonSpinner.getValue(), (Double) altSpinner.getValue());
            }
        }
    }

    private class MultiSiteSelector extends JPanel {
        private final JCheckBox mdraBox;
        private final JCheckBox spaarBox;
        private final JCheckBox customBox;
        private final JSpinner latSpinner;
        private final JSpinner lonSpinner;
        private final JSpinner altSpinner;
        private final JButton locateButton;

        MultiSiteSelector() {
            super(new FlowLayout(FlowLayout.LEFT, 6, 0));
            mdraBox = new JCheckBox(LaunchSite.MDRA_SOD_FARM.label, true);
            spaarBox = new JCheckBox(LaunchSite.SPAAR_LANCASTER.label, true);
            customBox = new JCheckBox("Custom...", false);
            latSpinner = new JSpinner(new SpinnerNumberModel(39.0, -90.0, 90.0, 0.0001));
            lonSpinner = new JSpinner(new SpinnerNumberModel(-76.1, -180.0, 180.0, 0.0001));
            altSpinner = new JSpinner(new SpinnerNumberModel(100.0, -500.0, 10000.0, 1.0));
            ((JSpinner.NumberEditor) latSpinner.getEditor()).getFormat().setMaximumFractionDigits(5);
            ((JSpinner.NumberEditor) lonSpinner.getEditor()).getFormat().setMaximumFractionDigits(5);
            latSpinner.setPreferredSize(new Dimension(90, latSpinner.getPreferredSize().height));
            lonSpinner.setPreferredSize(new Dimension(90, lonSpinner.getPreferredSize().height));
            altSpinner.setPreferredSize(new Dimension(70, altSpinner.getPreferredSize().height));
            locateButton = new JButton("Use Current Location");
            locateButton.setToolTipText("Resolve this machine's current position (on-device fix if available, "
                    + "otherwise an IP-geolocation approximation), check Custom, and fill it in.");

            setCustomFieldsEnabled(false);
            customBox.addActionListener(e -> setCustomFieldsEnabled(customBox.isSelected()));
            locateButton.addActionListener(e -> onLocateCurrentPosition());

            add(mdraBox);
            add(spaarBox);
            add(customBox);
            add(new JLabel("lat:"));
            add(latSpinner);
            add(new JLabel("lon:"));
            add(lonSpinner);
            add(new JLabel("alt(m):"));
            add(altSpinner);
            add(locateButton);
        }

        private void onLocateCurrentPosition() {
            locateButton.setEnabled(false);
            locateButton.setText("Locating...");
            new SwingWorker<DeviceLocation.Reading, Void>() {
                @Override
                protected DeviceLocation.Reading doInBackground() throws Exception {
                    return DeviceLocation.fetch(weatherApiKey());
                }

                @Override
                protected void done() {
                    locateButton.setEnabled(true);
                    locateButton.setText("Use Current Location");
                    try {
                        DeviceLocation.Reading r = get();
                        customBox.setSelected(true);
                        setCustomFieldsEnabled(true);
                        latSpinner.setValue(r.latitudeDeg);
                        lonSpinner.setValue(r.longitudeDeg);
                        altSpinner.setValue(r.altitudeM);
                        System.out.printf("Current location resolved via %s: %.5f, %.5f, %.0f m%n",
                                r.source, r.latitudeDeg, r.longitudeDeg, r.altitudeM);
                        System.out.println(r.accuracyNote);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        System.err.println("Could not resolve current location: " + cause.getMessage());
                        JOptionPane.showMessageDialog(MultiSiteSelector.this,
                                "Could not resolve current location:\n" + cause.getMessage(),
                                "Location unavailable", JOptionPane.WARNING_MESSAGE);
                    }
                }
            }.execute();
        }

        private void setCustomFieldsEnabled(boolean enabled) {
            latSpinner.setEnabled(enabled);
            lonSpinner.setEnabled(enabled);
            altSpinner.setEnabled(enabled);
        }

        List<LaunchSite> getSelectedSites() {
            List<LaunchSite> sites = new java.util.ArrayList<>();
            if (mdraBox.isSelected()) sites.add(LaunchSite.MDRA_SOD_FARM);
            if (spaarBox.isSelected()) sites.add(LaunchSite.SPAAR_LANCASTER);
            if (customBox.isSelected()) {
                sites.add(LaunchSite.custom((Double) latSpinner.getValue(), (Double) lonSpinner.getValue(), (Double) altSpinner.getValue()));
            }
            return sites;
        }

        void setSelectedSites(List<LaunchSite> sites) {
            mdraBox.setSelected(false);
            spaarBox.setSelected(false);
            customBox.setSelected(false);
            setCustomFieldsEnabled(false);
            for (LaunchSite site : sites) {
                if (site.latitudeDeg == LaunchSite.MDRA_SOD_FARM.latitudeDeg && site.longitudeDeg == LaunchSite.MDRA_SOD_FARM.longitudeDeg) {
                    mdraBox.setSelected(true);
                } else if (site.latitudeDeg == LaunchSite.SPAAR_LANCASTER.latitudeDeg && site.longitudeDeg == LaunchSite.SPAAR_LANCASTER.longitudeDeg) {
                    spaarBox.setSelected(true);
                } else {
                    customBox.setSelected(true);
                    setCustomFieldsEnabled(true);
                    latSpinner.setValue(site.latitudeDeg);
                    lonSpinner.setValue(site.longitudeDeg);
                    altSpinner.setValue(site.altitudeM);
                }
            }
        }

        List<String> getSelectedSiteSpecs() {
            List<String> specs = new java.util.ArrayList<>();
            if (mdraBox.isSelected()) specs.add("MDRA_SOD_FARM");
            if (spaarBox.isSelected()) specs.add("SPAAR_LANCASTER");
            if (customBox.isSelected()) {
                specs.add(String.format("CUSTOM:%s|%s|%s", latSpinner.getValue(), lonSpinner.getValue(), altSpinner.getValue()));
            }
            return specs;
        }
    }

    /** Restores a text field from the last session (if any) and saves every edit back in-memory for next launch. */
    private static void bindPersistentText(String key, JTextField field) {
        String saved = AppConfig.get().getField(key, null);
        if (saved != null && !saved.isBlank()) field.setText(saved);
        javax.swing.event.DocumentListener listener = new javax.swing.event.DocumentListener() {
            private void save() { AppConfig.get().setField(key, field.getText()); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { save(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save(); }
        };
        field.getDocument().addDocumentListener(listener);
    }

    /** Same as {@link #bindPersistentText}, but for a numeric JSpinner. */
    private static void bindPersistentSpinner(String key, JSpinner spinner) {
        String saved = AppConfig.get().getField(key, null);
        if (saved != null) {
            try {
                Object cur = spinner.getValue();
                if (cur instanceof Integer) spinner.setValue((int) Double.parseDouble(saved));
                else spinner.setValue(Double.parseDouble(saved));
            } catch (Exception ignored) {
            }
        }
        spinner.addChangeListener(e -> AppConfig.get().setField(key, String.valueOf(spinner.getValue())));
    }

    private interface Job {
        void run(ProgressListener listener) throws Exception;
    }

    private void runJob(String name, Job job) {
        setRunning(true);
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(true);
            progressBar.setValue(0);
            etaLabel.setText(" ");
        });
        appendLog(">>> Starting: " + name + "\n");

        ProgressListener guiListener = (processed, total, etaSeconds) -> SwingUtilities.invokeLater(() -> {
            if (total > 0) {
                progressBar.setIndeterminate(false);
                int pct = (int) Math.round(100.0 * processed / total);
                progressBar.setValue(Math.min(100, Math.max(0, pct)));
                progressBar.setString(processed + " / " + total);
            }
            etaLabel.setText(Double.isNaN(etaSeconds) ? " " : "ETA: " + EtaTracker.formatDuration(etaSeconds));
        });

        currentJob = jobExecutor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                job.run(guiListener);
                double secs = (System.currentTimeMillis() - start) / 1000.0;
                appendLog(String.format(">>> Finished: %s (%.1fs)%n", name, secs));
            } catch (Exception ex) {
                appendLog(">>> FAILED: " + name + " -- " + ex + "\n");
                for (StackTraceElement el : ex.getStackTrace()) {
                    appendLog("    at " + el + "\n");
                }
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setRunning(false);
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(0);
                    progressBar.setString("");
                    etaLabel.setText(" ");
                });
            }
        });
    }

    private void setRunning(boolean running) {
        statusLabel.setText(running ? "Running..." : "Idle");
        cancelButton.setEnabled(running);
    }

    private void appendLog(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            log.append(text);
            log.setCaretPosition(log.getDocument().getLength());
        } else {
            SwingUtilities.invokeLater(() -> appendLog(text));
        }
    }

    private void redirectSystemStreamsToLog() {
        PrintStream original = System.out;
        OutputStream teeOut = new OutputStream() {
            @Override
            public void write(int b) {
                original.write(b);
                appendLog(String.valueOf((char) b));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                original.write(b, off, len);
                appendLog(new String(b, off, len, StandardCharsets.UTF_8));
            }
        };
        PrintStream teeStream = new PrintStream(teeOut, true, StandardCharsets.UTF_8);
        System.setOut(teeStream);
        System.setErr(teeStream);
    }

    private JPanel withPadding(JPanel p) {
        p.setBorder(new EmptyBorder(12, 12, 12, 12));
        return p;
    }

    private JPanel buttonRow(JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : buttons) row.add(b);
        return row;
    }

    private JSplitPane verticalSplit(JComponent top, JComponent bottom, double resizeWeight) {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(resizeWeight);
        split.setOneTouchExpandable(true);
        split.setContinuousLayout(true);
        split.setBorder(null);
        return split;
    }

    private JPanel titledGroup(String title, JComponent content) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                new EmptyBorder(2, 4, 6, 4)));
        wrapper.add(content, BorderLayout.CENTER);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrapper;
    }

    private void stylePrimaryButton(JButton b) {
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        b.setMargin(new Insets(6, 16, 6, 16));
    }

    private JLabel hintLabel(String htmlBodyText) {
        JLabel label = new JLabel("<html><body style='width: 560px'><i>" + htmlBodyText + "</i></body></html>");
        label.setForeground(Color.GRAY);
        return label;
    }

    private File requireFile(JTextField field, String label) {
        String path = field.getText().trim();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please choose a " + label + ".", "Missing input", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        File f = new File(path);
        if (!f.exists()) {
            JOptionPane.showMessageDialog(this, "File not found: " + path, "Missing input", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return f;
    }

    private File requireExistingFile(JTextField field, String label) {
        return requireFile(field, label);
    }

    private File resolveOutDir(JTextField field, File orkFile, String defaultFolderName) {
        String path = field.getText().trim();
        if (!path.isEmpty()) return new File(path);
        return OutputNaming.namedSubfolder(orkFile, defaultFolderName);
    }

    private void openFileLocation(File f) {
        try {
            if (Desktop.isDesktopSupported() && f.getParentFile() != null) {
                Desktop.getDesktop().open(f.getParentFile());
            }
        } catch (Exception ignored) {

        }
    }

    private void openDirectory(File dir) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception ignored) {

        }
    }

    private void editPropertiesFile(File file) {
        try {
            String content = file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
            JTextArea editor = new JTextArea(content, 28, 70);
            editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scroll = new JScrollPane(editor);
            int result = JOptionPane.showConfirmDialog(this, scroll, "Edit " + file.getName(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result == JOptionPane.OK_OPTION) {
                Files.write(file.toPath(), editor.getText().getBytes(StandardCharsets.UTF_8));
                appendLog("Saved " + file.getAbsolutePath() + "\n");
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not read/write file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private class FormBuilder {
        private final JPanel p = new JPanel(new GridBagLayout());
        private int row = 0;

        JPanel panel() { return p; }

        void addRow(String label, JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST;
            p.add(new JLabel(label), c);
            c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
            p.add(field, c);
            row++;
        }

        JPanel addDirRow(String label, JTextField field) {
            JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
            rowPanel.add(field, BorderLayout.CENTER);
            JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JButton browse = new JButton("Browse...");
            browse.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser(lastDir);
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int result = chooser.showOpenDialog(ArcSimGui.this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    File f = chooser.getSelectedFile();
                    field.setText(f.getPath());
                    lastDir = f;
                }
            });
            buttonsPanel.add(browse);
            rowPanel.add(buttonsPanel, BorderLayout.EAST);

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST;
            p.add(new JLabel(label), c);
            c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
            p.add(rowPanel, c);
            row++;
            return buttonsPanel;
        }

        JPanel addAxisRow(String label, JSpinner minSpinner, JSpinner maxSpinner, JSpinner countSpinner) {
            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            minSpinner.setPreferredSize(new Dimension(75, minSpinner.getPreferredSize().height));
            maxSpinner.setPreferredSize(new Dimension(75, maxSpinner.getPreferredSize().height));
            countSpinner.setPreferredSize(new Dimension(60, countSpinner.getPreferredSize().height));
            rowPanel.add(minSpinner);
            rowPanel.add(new JLabel("to"));
            rowPanel.add(maxSpinner);
            rowPanel.add(new JLabel("at"));
            rowPanel.add(countSpinner);
            rowPanel.add(new JLabel("steps"));
            addRow(label, rowPanel);
            return rowPanel;
        }

        JPanel addFileRow(String label, JTextField field, boolean open, String filterDesc, String ext) {
            JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
            rowPanel.add(field, BorderLayout.CENTER);
            JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JButton browse = new JButton("Browse...");
            browse.addActionListener(e -> {
                JFileChooser chooser = new JFileChooser(lastDir);
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(filterDesc, ext));
                int result = open ? chooser.showOpenDialog(ArcSimGui.this) : chooser.showSaveDialog(ArcSimGui.this);
                if (result == JFileChooser.APPROVE_OPTION) {
                    File f = chooser.getSelectedFile();
                    if (!open && !f.getName().toLowerCase().endsWith("." + ext)) {
                        f = new File(f.getParentFile(), f.getName() + "." + ext);
                    }
                    field.setText(f.getPath());
                    lastDir = f.getParentFile() != null ? f.getParentFile() : lastDir;
                }
            });
            buttonsPanel.add(browse);
            rowPanel.add(buttonsPanel, BorderLayout.EAST);

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST;
            p.add(new JLabel(label), c);
            c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
            p.add(rowPanel, c);
            row++;
            return buttonsPanel;
        }
    }

    private static final class AxisFields {
        final String propKey;
        final JSpinner min, max, count;

        AxisFields(String propKey, double defMin, double defMax, int defCount, double spinnerStep) {
            this.propKey = propKey;
            this.min = new JSpinner(new SpinnerNumberModel(defMin, -100000.0, 100000.0, spinnerStep));
            this.max = new JSpinner(new SpinnerNumberModel(defMax, -100000.0, 100000.0, spinnerStep));
            this.count = new JSpinner(new SpinnerNumberModel(defCount, 1, 5000, 1));
        }

        GridAxis toGridAxis() {
            return GridAxis.fromRangeAndCount((Double) min.getValue(), (Double) max.getValue(), (Integer) count.getValue());
        }

        void loadFrom(GridAxis axis) {
            min.setValue(axis.min);
            max.setValue(axis.max);
            count.setValue(axis.count());
        }
    }

    private static final class ArcRocketDarkTheme extends DefaultMetalTheme {
        private final ColorUIResource bgDark = new ColorUIResource(0x1b1b22);
        private final ColorUIResource bgPanel = new ColorUIResource(0x24242c);
        private final ColorUIResource bgControl = new ColorUIResource(0x2c2c36);
        private final ColorUIResource borderGray = new ColorUIResource(0x3c3c48);
        private final ColorUIResource textLight = new ColorUIResource(0xe8e8ec);
        private final ColorUIResource textDim = new ColorUIResource(0xa0a0ac);
        private final ColorUIResource flameOrange = new ColorUIResource(0xff7a3d);
        private final ColorUIResource flameOrangeDim = new ColorUIResource(0xc65a2a);
        private final ColorUIResource flameOrangeBright = new ColorUIResource(0xffa46b);

        @Override
        public String getName() {
            return "ARC Rocket Dark";
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return flameOrangeDim;
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return flameOrange;
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return flameOrangeBright;
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return borderGray;
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return bgControl;
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return bgPanel;
        }

        @Override
        protected ColorUIResource getBlack() {
            return textLight;
        }

        @Override
        protected ColorUIResource getWhite() {
            return bgDark;
        }

        @Override
        public ColorUIResource getControlTextColor() {
            return textLight;
        }

        @Override
        public ColorUIResource getSystemTextColor() {
            return textLight;
        }

        @Override
        public ColorUIResource getUserTextColor() {
            return textLight;
        }

        @Override
        public ColorUIResource getInactiveControlTextColor() {
            return textDim;
        }

        @Override
        public ColorUIResource getInactiveSystemTextColor() {
            return textDim;
        }

        @Override
        public ColorUIResource getMenuDisabledForeground() {
            return textDim;
        }

        @Override
        public ColorUIResource getWindowTitleForeground() {
            return textLight;
        }

        @Override
        public ColorUIResource getWindowTitleBackground() {
            return bgPanel;
        }

        @Override
        public ColorUIResource getDesktopColor() {
            return bgDark;
        }

        @Override
        public ColorUIResource getFocusColor() {
            return flameOrange;
        }
    }

    private static void installDarkTheme() {
        try {
            MetalLookAndFeel.setCurrentTheme(new ArcRocketDarkTheme());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception ignored) {
            return;
        }
        fixTextComponentClipboardShortcuts();
        Color bgDark = new Color(0x1b1b22);
        Color bgConsole = new Color(0x151519);
        Color textLight = new Color(0xe8e8ec);
        Color flameOrange = new Color(0xff7a3d);
        Color borderGray = new Color(0x3c3c48);

        UIManager.put("TextArea.background", bgConsole);
        UIManager.put("TextArea.foreground", textLight);
        UIManager.put("TextArea.caretForeground", flameOrange);
        UIManager.put("TextField.background", bgDark);
        UIManager.put("TextField.foreground", textLight);
        UIManager.put("TextField.caretForeground", flameOrange);
        UIManager.put("FormattedTextField.background", bgDark);
        UIManager.put("FormattedTextField.foreground", textLight);
        UIManager.put("Spinner.background", bgDark);
        UIManager.put("Spinner.foreground", textLight);
        UIManager.put("ComboBox.background", bgDark);
        UIManager.put("ComboBox.foreground", textLight);
        UIManager.put("List.background", bgDark);
        UIManager.put("List.foreground", textLight);
        UIManager.put("List.selectionBackground", flameOrange);
        UIManager.put("Table.background", bgDark);
        UIManager.put("Table.foreground", textLight);
        UIManager.put("Table.gridColor", borderGray);
        UIManager.put("Table.selectionBackground", flameOrange);
        UIManager.put("TableHeader.background", new Color(0x24242c));
        UIManager.put("TableHeader.foreground", textLight);
        UIManager.put("ScrollPane.background", bgDark);
        UIManager.put("Viewport.background", bgDark);
        UIManager.put("ProgressBar.foreground", flameOrange);
        UIManager.put("ProgressBar.selectionForeground", bgDark);
        UIManager.put("ProgressBar.selectionBackground", textLight);
        UIManager.put("ToolTip.background", new Color(0x2c2c36));
        UIManager.put("ToolTip.foreground", textLight);
        UIManager.put("OptionPane.background", bgDark);
        UIManager.put("Panel.background", bgDark);
        UIManager.put("PopupMenu.background", new Color(0x24242c));
        UIManager.put("MenuItem.background", new Color(0x24242c));
        UIManager.put("MenuItem.foreground", textLight);
    }

    private static void fixTextComponentClipboardShortcuts() {

        int shortcut;
        try {
            shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (Exception e) {
            return;
        }
        String[] inputMapKeys = {
                "TextField.focusInputMap", "FormattedTextField.focusInputMap",
                "PasswordField.focusInputMap", "TextArea.focusInputMap", "EditorPane.focusInputMap",
                "TextPane.focusInputMap"
        };
        for (String key : inputMapKeys) {
            Object value = UIManager.get(key);
            if (!(value instanceof InputMap)) continue;
            InputMap im = (InputMap) value;
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut), DefaultEditorKit.copyAction);
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcut), DefaultEditorKit.pasteAction);
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcut), DefaultEditorKit.cutAction);
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut), DefaultEditorKit.selectAllAction);
        }
    }

    private static Image loadAppIcon() {
        try {
            java.net.URL url = ArcSimGui.class.getResource("/com/arc/sim/icon.png");
            if (url == null) return null;
            return Toolkit.getDefaultToolkit().createImage(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static void installDockIcon(Image icon) {
        if (icon == null) return;
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icon);
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {

        }
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem prefsItem = new JMenuItem("Preferences...");
        prefsItem.addActionListener(e -> showPreferencesDialog(this, false));
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> dispose());
        fileMenu.add(prefsItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem quickstartItem = new JMenuItem("Open Quickstart Guide");
        quickstartItem.addActionListener(e -> openQuickstartGuide());
        JMenuItem aboutItem = new JMenuItem("About Arc-Sim");
        aboutItem.addActionListener(e -> showAboutDialog(this));
        helpMenu.add(quickstartItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        bar.add(fileMenu);
        bar.add(helpMenu);
        return bar;
    }

    private void openQuickstartGuide() {
        File guide = new File(lastDir, "QUICKSTART.md");
        if (!guide.isFile()) {

            File candidate = new File(AppConfig.appDir(), "QUICKSTART.md");
            if (candidate.isFile()) guide = candidate;
        }
        if (!guide.isFile()) {
            JOptionPane.showMessageDialog(this,
                    "QUICKSTART.md was not found alongside the application. It should be distributed " +
                            "in the same folder as the jar/launcher.",
                    "Quickstart guide not found", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(guide);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not open " + guide.getAbsolutePath() + ": " + e.getMessage(),
                    "Could not open guide", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void showPreferencesDialog(Component parent, boolean firstRun) {
        AppConfig cfg = AppConfig.get();

        JTextField apiKeyField = new JTextField(cfg.weatherApiKey, 28);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0; gc.gridy = 0;
        int row = 0;

        if (firstRun) {
            JLabel welcome = new JLabel("<html><b>Welcome to Arc-Sim</b><br>" +
                    "This app is self-contained -- it always opens file dialogs starting in its own " +
                    "folder, and writes nothing elsewhere on this computer except the optional " +
                    "setting below.</html>");
            gc.gridx = 0; gc.gridy = row++; gc.gridwidth = 2;
            form.add(welcome, gc);
            gc.gridwidth = 1;
        }

        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("Weather API key (optional):"), gc);
        gc.gridx = 0; gc.gridy = ++row; gc.gridwidth = 2;
        form.add(apiKeyField, gc);
        gc.gridwidth = 1;
        row++;

        JLabel hint = new JLabel("<html><font color=gray>Used by Engine 4 (live weather) and " +
                "\"Use Current Location\". Get a free key at weatherapi.com &mdash; you can skip " +
                "this and set it later via File &gt; Preferences.</font></html>");
        gc.gridx = 0; gc.gridy = ++row; gc.gridwidth = 2;
        form.add(hint, gc);

        String title = firstRun ? "Arc-Sim -- First-Run Setup" : "Preferences";
        String[] options = firstRun ? new String[]{"Get Started"} : new String[]{"OK", "Cancel"};
        int result = JOptionPane.showOptionDialog(parent, form, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);

        boolean accepted = result == 0;
        if (accepted) {
            cfg.weatherApiKey = apiKeyField.getText().trim();
            cfg.firstRunComplete = true;
            cfg.save();
        } else if (firstRun) {

            cfg.firstRunComplete = true;
            cfg.save();
        }
    }

    private static void showAboutDialog(Component parent) {
        String message = "<html><div style='width:340px'>" +
                "<h2 style='margin-bottom:0'>Arc-Sim</h2>" +
                "<p style='margin-top:2px'>Version " + APP_VERSION + "</p>" +
                "<p>Rocket flight simulation and design toolkit built on the OpenRocket core " +
                "simulation engine (RK4 integration, Barrowman aerodynamics). Four engines: " +
                "Full Factorial Sweep, Design Solver, Geometry Export, and Weather-Driven Design.</p>" +
                "<p style='color:gray'>OpenRocket core is used under its own open-source license; " +
                "see the project README for third-party notices.</p>" +
                "</div></html>";
        JOptionPane.showMessageDialog(parent, message, "About Arc-Sim", JOptionPane.PLAIN_MESSAGE,
                loadAppIcon() != null ? new ImageIcon(loadAppIcon().getScaledInstance(64, 64, Image.SCALE_SMOOTH)) : null);
    }

    public static void main(String[] args) {

        System.setProperty("log4j2.loggerContextFactory", "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");

        installDockIcon(loadAppIcon());
        if (!AppConfig.get().firstRunComplete) {
            SwingUtilities.invokeLater(() -> {
                installDarkTheme();
                showPreferencesDialog(null, true);
                new ArcSimGui().setVisible(true);
            });
        } else {
            SwingUtilities.invokeLater(() -> {
                installDarkTheme();
                new ArcSimGui().setVisible(true);
            });
        }
    }
}

