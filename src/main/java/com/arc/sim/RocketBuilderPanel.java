package com.arc.sim;

import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import info.openrocket.core.material.Material;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.*;
import info.openrocket.core.rocketcomponent.position.AxialMethod;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

public class RocketBuilderPanel extends JPanel {

    private RocketBuilderModel model;
    private File currentFile;
    private boolean dirty;
    private File lastDir = AppConfig.appDir();

    private final JTree tree = new JTree();
    private ComponentTreeModel treeModel;
    private final JPanel propertyContainer = new JPanel(new BorderLayout());
    private final RocketPreviewPanel previewPanel = new RocketPreviewPanel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel stabilityLabel = new JLabel(" ");

    private final JButton removeButton = new JButton("Remove Component");
    private final JButton upButton = new JButton("Move Up");
    private final JButton downButton = new JButton("Move Down");
    private final JButton undoButton = new JButton("Undo");
    private final JButton redoButton = new JButton("Redo");

    public RocketBuilderPanel() {
        super(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(12, 12, 12, 12));
        this.model = RocketBuilderModel.newRocket();
        buildUi();
        refreshAll();
    }

    private void buildUi() {
        JPanel top = new JPanel(new BorderLayout(4, 4));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton newButton = new JButton("New Rocket");
        JButton openButton = new JButton("Open .ork...");
        JButton saveButton = new JButton("Save");
        JButton saveAsButton = new JButton("Save As...");
        JButton addButton = new JButton("Add Component ▾");

        newButton.addActionListener(e -> doNew());
        openButton.addActionListener(e -> doOpen());
        saveButton.addActionListener(e -> doSave());
        saveAsButton.addActionListener(e -> doSaveAs());
        addButton.addActionListener(e -> showAddMenu(addButton));
        removeButton.addActionListener(e -> doRemove());
        upButton.addActionListener(e -> doMove(true));
        downButton.addActionListener(e -> doMove(false));
        undoButton.addActionListener(e -> doUndo());
        redoButton.addActionListener(e -> doRedo());

        toolbar.add(newButton);
        toolbar.add(openButton);
        toolbar.add(saveButton);
        toolbar.add(saveAsButton);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(undoButton);
        toolbar.add(redoButton);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(addButton);
        toolbar.add(removeButton);
        toolbar.add(upButton);
        toolbar.add(downButton);

        String undoKey = "arcSimUndo", redoKey = "arcSimRedo";
        int menuShortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menuShortcutMask), undoKey);
        getActionMap().put(undoKey, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { doUndo(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, menuShortcutMask), redoKey);
        getActionMap().put(redoKey, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { doRedo(); }
        });

        JLabel hint = new JLabel("<html><body style='width: 900px'><i>Build a rocket from scratch: " +
                "select a node on the left and click \"Add Component\" to attach nose cones, body tubes, " +
                "transitions, fin sets, internal structure, recovery hardware, or ballast beneath it. Edit " +
                "geometry, material, and position on the right. Any body tube or inner tube can be made a " +
                "motor mount and assigned a real motor from the bundled thrust-curve database. Save as a " +
                "plain .ork file usable anywhere else in Arc-Sim.</i></body></html>");
        hint.setForeground(Color.GRAY);

        top.add(toolbar, BorderLayout.NORTH);
        top.add(hint, BorderLayout.SOUTH);

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new ComponentTreeCellRenderer());
        tree.addTreeSelectionListener(e -> onSelectionChanged());

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Component Tree"));
        treeScroll.setPreferredSize(new Dimension(300, 400));

        propertyContainer.setBorder(BorderFactory.createTitledBorder("Properties"));

        JSplitPane treeAndProps = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, propertyContainer);
        treeAndProps.setResizeWeight(0.32);
        treeAndProps.setOneTouchExpandable(true);
        treeAndProps.setContinuousLayout(true);

        previewPanel.setPreferredSize(new Dimension(900, 190));
        previewPanel.setBorder(BorderFactory.createTitledBorder("Live Preview (approximate schematic, not to-scale CAD)"));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeAndProps, previewPanel);
        mainSplit.setResizeWeight(0.65);
        mainSplit.setOneTouchExpandable(true);
        mainSplit.setContinuousLayout(true);

        statusLabel.setForeground(Color.GRAY);

        stabilityLabel.setFont(stabilityLabel.getFont().deriveFont(Font.BOLD, 12f));
        stabilityLabel.setBorder(new EmptyBorder(2, 2, 6, 2));

        JPanel northWrap = new JPanel(new BorderLayout(0, 4));
        northWrap.add(top, BorderLayout.NORTH);
        northWrap.add(stabilityLabel, BorderLayout.SOUTH);

        setLayout(new BorderLayout(8, 8));
        add(northWrap, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        updateUndoRedoButtons();
    }

    private boolean confirmDiscardIfDirty() {
        if (!dirty) return true;
        int result = JOptionPane.showConfirmDialog(this,
                "Discard unsaved changes to the current rocket?", "Unsaved changes",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }

    private void doNew() {
        if (!confirmDiscardIfDirty()) return;
        model = RocketBuilderModel.newRocket();
        currentFile = null;
        dirty = false;
        refreshAll();
    }

    private void doOpen() {
        if (!confirmDiscardIfDirty()) return;
        JFileChooser chooser = new JFileChooser(lastDir);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("OpenRocket files (*.ork)", "ork"));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        lastDir = f.getParentFile();
        try {
            model = RocketBuilderModel.loadFromOrk(f);
            currentFile = f;
            dirty = false;
            refreshAll();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not load rocket: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doSave() {
        if (currentFile == null) {
            doSaveAs();
            return;
        }
        try {
            model.saveToOrk(currentFile);
            dirty = false;
            updateStatus();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doSaveAs() {
        File startDir = lastDir;
        if (currentFile == null && lastDir.equals(AppConfig.appDir())) {
            startDir = OutputNaming.appRelativeFolder(AppConfig.appDir(), OutputNaming.ROCKET_BUILDER_FOLDER);
        }
        JFileChooser chooser = new JFileChooser(startDir);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("OpenRocket files (*.ork)", "ork"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".ork")) {
            f = new File(f.getParentFile(), f.getName() + ".ork");
        }
        lastDir = f.getParentFile();
        try {
            model.saveToOrk(f);
            currentFile = f;
            dirty = false;
            updateStatus();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private RocketComponent getSelectedComponent() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        return (RocketComponent) path.getLastPathComponent();
    }

    private void selectComponent(RocketComponent c) {
        List<RocketComponent> chain = new ArrayList<>();
        RocketComponent cur = c;
        while (cur != null) {
            chain.add(0, cur);
            cur = cur.getParent();
        }
        TreePath path = new TreePath(chain.toArray());
        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);
    }

    private void showAddMenu(JComponent anchor) {
        RocketComponent selected = getSelectedComponent();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Select a node in the tree first (e.g. the rocket root or a stage) to add a component to it.");
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        boolean any = false;
        for (RocketBuilderModel.ComponentType type : RocketBuilderModel.ComponentType.values()) {
            if (type.isAddableTo(selected)) {
                any = true;
                JMenuItem item = new JMenuItem(type.displayName);
                item.addActionListener(e -> {
                    RocketComponent[] addedHolder = new RocketComponent[1];
                    withUndo("Add " + type.displayName, () -> addedHolder[0] = model.addComponent(selected, type));
                    treeModel.fireStructureChanged(selected);
                    expandAll();
                    selectComponent(addedHolder[0]);
                    refreshPreview();
                });
                menu.add(item);
            }
        }
        if (!any) {
            JMenuItem none = new JMenuItem("(nothing can be added to this component)");
            none.setEnabled(false);
            menu.add(none);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void doRemove() {
        RocketComponent selected = getSelectedComponent();
        if (selected == null || selected.getParent() == null) {
            JOptionPane.showMessageDialog(this, "Select a removable component (not the rocket root) first.");
            return;
        }
        RocketComponent parent = selected.getParent();
        withUndo("Remove component", () -> model.removeComponent(selected));
        treeModel.fireStructureChanged(parent);
        expandAll();
        selectComponent(parent);
        refreshPreview();
    }

    private void doMove(boolean up) {
        RocketComponent selected = getSelectedComponent();
        if (selected == null || selected.getParent() == null) return;
        RocketComponent parent = selected.getParent();
        withUndo(up ? "Move component up" : "Move component down", () -> {
            if (up) model.moveComponentUp(selected);
            else model.moveComponentDown(selected);
        });
        treeModel.fireStructureChanged(parent);
        expandAll();
        selectComponent(selected);
        refreshPreview();
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void onSelectionChanged() {
        RocketComponent selected = getSelectedComponent();
        boolean removable = selected != null && selected.getParent() != null;
        removeButton.setEnabled(removable);
        upButton.setEnabled(removable);
        downButton.setEnabled(removable);
        showPropertiesFor(selected);
    }

    private void refreshAll() {
        treeModel = new ComponentTreeModel(model.getRocket());
        tree.setModel(treeModel);
        expandAll();
        selectComponent(model.getRocket());
        refreshPreview();
        updateStatus();
        updateUndoRedoButtons();
    }

    private void refreshPreview() {
        String label = (currentFile != null ? currentFile.getName() : "New Rocket (unsaved)") + (dirty ? " *" : "");
        previewPanel.setGeometry(model.previewGeometry(), label);
        refreshStability();
    }

    private void refreshStability() {
        RocketBuilderModel.StabilityInfo info = model.computeStability();
        if (!info.ok) {
            stabilityLabel.setText(info.error);
            stabilityLabel.setForeground(Color.GRAY);
            return;
        }
        String ratingText = switch (info.rating) {
            case UNSTABLE -> "UNSTABLE";
            case MARGINAL -> "MARGINAL";
            case STABLE -> "STABLE";
            case OVERSTABLE -> "OVERSTABLE";
            default -> "UNKNOWN";
        };
        Color color = switch (info.rating) {
            case UNSTABLE -> new Color(180, 30, 30);
            case MARGINAL -> new Color(190, 130, 0);
            case STABLE -> new Color(30, 130, 60);
            case OVERSTABLE -> new Color(30, 90, 160);
            default -> Color.GRAY;
        };
        stabilityLabel.setForeground(color);
        stabilityLabel.setText(String.format("Stability: %s -- %.2f calibers  (CG %.3f m, CP %.3f m, mass %.3f kg)",
                ratingText, info.marginCalibers, info.cgXM, info.cpXM, info.massKg));
    }

    private void withUndo(String description, Runnable mutation) {
        info.openrocket.core.document.OpenRocketDocument doc = model.getDocument();
        doc.startUndo(description);
        try {
            mutation.run();
        } finally {
            doc.stopUndo();
        }
        markDirty();
        updateUndoRedoButtons();
    }

    private void doUndo() {
        info.openrocket.core.document.OpenRocketDocument doc = model.getDocument();
        if (!doc.isUndoAvailable()) return;
        doc.undo();
        afterUndoRedo();
    }

    private void doRedo() {
        info.openrocket.core.document.OpenRocketDocument doc = model.getDocument();
        if (!doc.isRedoAvailable()) return;
        doc.redo();
        afterUndoRedo();
    }

    private void afterUndoRedo() {
        RocketComponent selected = getSelectedComponent();
        treeModel.fireStructureChanged(model.getRocket());
        expandAll();
        if (selected != null && isStillInTree(selected)) {
            selectComponent(selected);
        } else {
            selectComponent(model.getRocket());
        }
        markDirty();
        refreshPreview();
        updateUndoRedoButtons();
    }

    private boolean isStillInTree(RocketComponent c) {
        RocketComponent cur = c;
        while (cur.getParent() != null) cur = cur.getParent();
        return cur == model.getRocket();
    }

    private void updateUndoRedoButtons() {
        info.openrocket.core.document.OpenRocketDocument doc = model.getDocument();
        undoButton.setEnabled(doc.isUndoAvailable());
        redoButton.setEnabled(doc.isRedoAvailable());
        undoButton.setToolTipText(doc.isUndoAvailable() ? "Undo: " + doc.getUndoDescription() : "Nothing to undo");
        redoButton.setToolTipText(doc.isRedoAvailable() ? "Redo: " + doc.getRedoDescription() : "Nothing to redo");
    }

    private void markDirty() {
        dirty = true;
        updateStatus();
    }

    private void updateStatus() {
        String name = currentFile != null ? currentFile.getAbsolutePath() : "unsaved new rocket";
        statusLabel.setText((dirty ? "* " : "") + name);
        refreshPreview();
    }

    private void showPropertiesFor(RocketComponent c) {
        propertyContainer.removeAll();
        propertyContainer.add(buildPropertyPanel(c), BorderLayout.CENTER);
        propertyContainer.revalidate();
        propertyContainer.repaint();
    }

    private void refreshSelectedPropertyPanel() {
        showPropertiesFor(getSelectedComponent());
        refreshPreview();
    }

    private void applyName(RocketComponent c, JTextField field) {
        String v = field.getText();
        if (v == null) v = "";
        if (!v.equals(c.getName())) {
            String finalV = v;
            withUndo("Rename component", () -> c.setName(finalV));
            treeModel.fireNodeChanged(c);
        }
    }

    private JSpinner meterSpinner(double value, double min, double max, double step, DoubleConsumer onChange) {
        double lo = Math.min(min, value);
        double hi = Math.max(max, value);
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, lo, hi, step));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.0000"));
        ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().setColumns(8);
        spinner.addChangeListener(e -> {
            withUndo("Edit dimension", () -> onChange.accept(((Number) spinner.getValue()).doubleValue()));
            refreshPreview();
        });
        return spinner;
    }

    private JSpinner kgSpinner(double value, double min, double max, double step, DoubleConsumer onChange) {
        double lo = Math.min(min, value);
        double hi = Math.max(max, value);
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, lo, hi, step));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.000"));
        spinner.addChangeListener(e -> {
            withUndo("Edit mass", () -> onChange.accept(((Number) spinner.getValue()).doubleValue()));
            refreshPreview();
        });
        return spinner;
    }

    private JSpinner intSpinner(int value, int min, int max, IntConsumer onChange) {
        int lo = Math.min(min, value);
        int hi = Math.max(max, value);
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, lo, hi, 1));
        spinner.addChangeListener(e -> {
            withUndo("Edit count", () -> onChange.accept((Integer) spinner.getValue()));
            refreshPreview();
        });
        return spinner;
    }

    private JComboBox<Material> materialCombo(List<Material> options, Material current, java.util.function.Consumer<Material> onChange) {
        JComboBox<Material> combo = new JComboBox<>(options.toArray(new Material[0]));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Material m) {
                    setText(m.getName() + String.format("  (%.0f %s)", m.getDensity(),
                            m.getType() == Material.Type.BULK ? "kg/m³" : m.getType() == Material.Type.SURFACE ? "kg/m²" : "kg/m"));
                }
                return this;
            }
        });
        Material match = null;
        for (Material m : options) {
            if (current != null && m.getName().equals(current.getName())) {
                match = m;
                break;
            }
        }
        if (match != null) {
            combo.setSelectedItem(match);
        } else if (current != null) {
            combo.insertItemAt(current, 0);
            combo.setSelectedItem(current);
        }
        combo.addActionListener(e -> {
            withUndo("Change material", () -> onChange.accept((Material) combo.getSelectedItem()));
            refreshPreview();
        });
        return combo;
    }

    private List<Material> catalogFor(Material.Type type) {
        return RocketBuilderModel.MaterialCatalog.materialsFor(type);
    }

    private JPanel materialRow(Material.Type type, Material current, java.util.function.Consumer<Material> onChange) {
        JComboBox<Material> combo = materialCombo(catalogFor(type), current, onChange);
        JButton addBtn = new JButton("+");
        addBtn.setToolTipText("Define a custom material");
        addBtn.setMargin(new Insets(0, 6, 0, 6));
        addBtn.addActionListener(e -> {
            Material created = showAddMaterialDialog(type);
            if (created != null) {
                onChange.accept(created);
                markDirty();
                refreshSelectedPropertyPanel();
            }
        });
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(combo, BorderLayout.CENTER);
        row.add(addBtn, BorderLayout.EAST);
        return row;
    }

    private String densityUnitFor(Material.Type type) {
        return type == Material.Type.BULK ? "kg/m³" : type == Material.Type.SURFACE ? "kg/m²" : "kg/m";
    }

    private Material showAddMaterialDialog(Material.Type type) {
        JTextField nameField = new JTextField();
        JSpinner densitySpinner = new JSpinner(new SpinnerNumberModel(1000.0, 0.001, 20000.0, 10.0));
        densitySpinner.setEditor(new JSpinner.NumberEditor(densitySpinner, "0.000"));

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("Name:"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        nameField.setColumns(20);
        panel.add(nameField, c);
        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(new JLabel("Density (" + densityUnitFor(type) + "):"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(densitySpinner, c);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        panel.add(hint("Adds a new material to the " + type.toString().toLowerCase() +
                " catalog for the rest of this session -- it'll show up in every material dropdown " +
                "from now on, in this rocket and any other you open."), c);

        while (true) {
            int result = JOptionPane.showConfirmDialog(this, panel, "Add Custom Material",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return null;
            String name = nameField.getText().trim();
            double density = ((Number) densitySpinner.getValue()).doubleValue();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Give the material a name.", "Missing name", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            try {
                return RocketBuilderModel.MaterialCatalog.addCustom(type, name, density);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not add material: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private JLabel hint(String text) {
        JLabel l = new JLabel("<html><body style='width: 420px'><i>" + text + "</i></body></html>");
        l.setForeground(Color.GRAY);
        return l;
    }

    private String humanTypeName(RocketComponent c) {
        return c.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2");
    }

    private void addPositionSection(PropForm form, RocketComponent c) {
        form.addSection("Position");
        JComboBox<AxialMethod> methodCombo = new JComboBox<>(AxialMethod.values());
        methodCombo.setSelectedItem(c.getAxialMethod());
        methodCombo.addActionListener(e -> {
            withUndo("Change axial method", () -> c.setAxialMethod((AxialMethod) methodCombo.getSelectedItem()));
            refreshPreview();
        });
        form.addRow("Axial method:", methodCombo);
        form.addRow("Axial offset (m):", meterSpinner(c.getAxialOffset(), -5.0, 5.0, 0.001, c::setAxialOffset));
        form.addRow("", hint("TOP/BOTTOM/MIDDLE/AFTER position relative to the previous sibling (or parent), " +
                "or ABSOLUTE to measure from the nose. Offset is added on top of that reference point."));
    }

    private void addMotorMountSection(PropForm form, RocketComponent c) {
        MotorMount mount = (MotorMount) c;
        form.addSection("Motor Mount");
        JCheckBox mountBox = new JCheckBox("This component is a motor mount", mount.isMotorMount());
        mountBox.addActionListener(e -> withUndo("Toggle motor mount", () -> mount.setMotorMount(mountBox.isSelected())));
        form.addRow("", mountBox);
        form.addRow("Current motor:", new JLabel(describeMotor(mount)));
        JButton selectBtn = new JButton("Select Motor...");
        selectBtn.addActionListener(e -> showMotorPicker(c));
        JButton clearBtn = new JButton("Clear Motor");
        clearBtn.addActionListener(e -> {
            withUndo("Clear motor", () -> model.clearMotor(mount));
            refreshSelectedPropertyPanel();
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(selectBtn);
        row.add(clearBtn);
        form.addRow("", row);
    }

    private String describeMotor(MotorMount mount) {
        MotorConfiguration conf = mount.getMotorConfig(model.getActiveFcid());
        Motor motor = conf.getMotor();
        if (motor == null) return "(none)";
        if (motor instanceof ThrustCurveMotor tcm) {
            return tcm.getManufacturer() + " " + tcm.getDesignation() + "  (" +
                    String.format("%.0f Ns, %.1fs burn", tcm.getTotalImpulseEstimate(), tcm.getBurnTimeEstimate()) + ")";
        }
        return motor.toString();
    }

    private double motorMountDiameterHint(RocketComponent c) {
        try {
            if (c instanceof BodyTube bt) return bt.getInnerRadius() * 2.0;
            if (c instanceof InnerTube it) return it.getInnerRadius() * 2.0;
        } catch (Exception ignored) {
        }
        return 0.029;
    }

    @SuppressWarnings("unchecked")
    private JComponent buildPropertyPanel(RocketComponent c) {
        if (c == null) {
            return new JLabel("Select a component in the tree.");
        }
        PropForm form = new PropForm();

        JTextField nameField = new JTextField(c.getName());
        nameField.addActionListener(e -> applyName(c, nameField));
        nameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                applyName(c, nameField);
            }
        });
        form.addRow("Name:", nameField);

        if (!(c instanceof Rocket) && !(c instanceof AxialStage)) {
            addPositionSection(form, c);
        }

        form.addSection(humanTypeName(c));

        if (c instanceof NoseCone nc) {
            JComboBox<Transition.Shape> shapeCombo = new JComboBox<>(Transition.Shape.values());
            shapeCombo.setSelectedItem(nc.getShapeType());
            shapeCombo.addActionListener(e -> {
                nc.setShapeType((Transition.Shape) shapeCombo.getSelectedItem());
                markDirty();
                refreshPreview();
            });
            form.addRow("Shape:", shapeCombo);
            form.addRow("Length (m):", meterSpinner(nc.getLength(), 0.001, 5.0, 0.001, nc::setLength));

            JSpinner baseRadiusSpinner = meterSpinner(nc.getBaseRadius(), 0.001, 1.0, 0.001, nc::setBaseRadius);
            baseRadiusSpinner.setEnabled(!nc.isBaseRadiusAutomatic());
            JCheckBox autoBase = new JCheckBox("Auto", nc.isBaseRadiusAutomatic());
            autoBase.addActionListener(e -> {
                nc.setBaseRadiusAutomatic(autoBase.isSelected());
                baseRadiusSpinner.setEnabled(!autoBase.isSelected());
                markDirty();
                refreshPreview();
            });
            JPanel baseRow = new JPanel(new BorderLayout(4, 0));
            baseRow.add(baseRadiusSpinner, BorderLayout.CENTER);
            baseRow.add(autoBase, BorderLayout.EAST);
            form.addRow("Base radius (m):", baseRow);

            form.addRow("Wall thickness (m):", meterSpinner(nc.getThickness(), 0.0001, 0.05, 0.0001, nc::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, nc.getMaterial(), nc::setMaterial));

        } else if (c instanceof Transition t) {
            JComboBox<Transition.Shape> shapeCombo = new JComboBox<>(Transition.Shape.values());
            shapeCombo.setSelectedItem(t.getShapeType());
            shapeCombo.addActionListener(e -> {
                t.setShapeType((Transition.Shape) shapeCombo.getSelectedItem());
                markDirty();
                refreshPreview();
            });
            form.addRow("Shape:", shapeCombo);
            form.addRow("Length (m):", meterSpinner(t.getLength(), 0.001, 5.0, 0.001, t::setLength));

            JSpinner foreSpinner = meterSpinner(t.getForeRadius(), 0.001, 1.0, 0.001, v -> t.setForeRadius(v));
            foreSpinner.setEnabled(!t.isForeRadiusAutomatic());
            JCheckBox foreAuto = new JCheckBox("Auto", t.isForeRadiusAutomatic());
            foreAuto.addActionListener(e -> {
                t.setForeRadiusAutomatic(foreAuto.isSelected());
                foreSpinner.setEnabled(!foreAuto.isSelected());
                markDirty();
                refreshPreview();
            });
            JPanel foreRow = new JPanel(new BorderLayout(4, 0));
            foreRow.add(foreSpinner, BorderLayout.CENTER);
            foreRow.add(foreAuto, BorderLayout.EAST);
            form.addRow("Fore radius (m):", foreRow);

            JSpinner aftSpinner = meterSpinner(t.getAftRadius(), 0.001, 1.0, 0.001, v -> t.setAftRadius(v));
            aftSpinner.setEnabled(!t.isAftRadiusAutomatic());
            JCheckBox aftAuto = new JCheckBox("Auto", t.isAftRadiusAutomatic());
            aftAuto.addActionListener(e -> {
                t.setAftRadiusAutomatic(aftAuto.isSelected());
                aftSpinner.setEnabled(!aftAuto.isSelected());
                markDirty();
                refreshPreview();
            });
            JPanel aftRow = new JPanel(new BorderLayout(4, 0));
            aftRow.add(aftSpinner, BorderLayout.CENTER);
            aftRow.add(aftAuto, BorderLayout.EAST);
            form.addRow("Aft radius (m):", aftRow);

            form.addRow("Wall thickness (m):", meterSpinner(t.getThickness(), 0.0001, 0.05, 0.0001, t::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, t.getMaterial(), t::setMaterial));

        } else if (c instanceof BodyTube bt) {
            form.addRow("Length (m):", meterSpinner(bt.getLength(), 0.001, 10.0, 0.001, bt::setLength));

            JSpinner radiusSpinner = meterSpinner(bt.getOuterRadius(), 0.001, 1.0, 0.001, bt::setOuterRadius);
            radiusSpinner.setEnabled(!bt.isOuterRadiusAutomatic());
            JCheckBox autoRadius = new JCheckBox("Auto", bt.isOuterRadiusAutomatic());
            autoRadius.addActionListener(e -> {
                bt.setOuterRadiusAutomatic(autoRadius.isSelected());
                radiusSpinner.setEnabled(!autoRadius.isSelected());
                markDirty();
                refreshPreview();
            });
            JPanel radiusRow = new JPanel(new BorderLayout(4, 0));
            radiusRow.add(radiusSpinner, BorderLayout.CENTER);
            radiusRow.add(autoRadius, BorderLayout.EAST);
            form.addRow("Outer radius (m):", radiusRow);

            form.addRow("Wall thickness (m):", meterSpinner(bt.getThickness(), 0.0001, 0.05, 0.0001, bt::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, bt.getMaterial(), bt::setMaterial));
            addMotorMountSection(form, bt);

        } else if (c instanceof TrapezoidFinSet f) {
            form.addRow("Fin count:", intSpinner(f.getFinCount(), 1, 12, f::setFinCount));
            form.addRow("Root chord (m):", meterSpinner(f.getRootChord(), 0.005, 2.0, 0.001, f::setRootChord));
            form.addRow("Tip chord (m):", meterSpinner(f.getTipChord(), 0.0, 2.0, 0.001, f::setTipChord));
            form.addRow("Sweep (m):", meterSpinner(f.getSweep(), -1.0, 2.0, 0.001, f::setSweep));
            form.addRow("Height/span (m):", meterSpinner(f.getHeight(), 0.005, 1.0, 0.001, f::setHeight));
            form.addRow("Thickness (m):", meterSpinner(f.getThickness(), 0.0005, 0.05, 0.0005, f::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, f.getMaterial(), f::setMaterial));

        } else if (c instanceof EllipticalFinSet f) {
            form.addRow("Fin count:", intSpinner(f.getFinCount(), 1, 12, f::setFinCount));
            form.addRow("Root chord / length (m):", meterSpinner(f.getLength(), 0.005, 2.0, 0.001, f::setLength));
            form.addRow("Height/span (m):", meterSpinner(f.getHeight(), 0.005, 1.0, 0.001, f::setHeight));
            form.addRow("Thickness (m):", meterSpinner(f.getThickness(), 0.0005, 0.05, 0.0005, f::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, f.getMaterial(), f::setMaterial));

        } else if (c instanceof InnerTube it) {
            form.addRow("Length (m):", meterSpinner(it.getLength(), 0.001, 5.0, 0.001, it::setLength));
            form.addRow("Outer radius (m):", meterSpinner(it.getOuterRadius(), 0.001, 0.5, 0.0005, it::setOuterRadius));
            form.addRow("Wall thickness (m):", meterSpinner(it.getThickness(), 0.0001, 0.05, 0.0001, it::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, it.getMaterial(), it::setMaterial));
            addMotorMountSection(form, it);

        } else if (c instanceof CenteringRing r) {
            form.addRow("Outer radius (m):", meterSpinner(r.getOuterRadius(), 0.001, 0.5, 0.0005, r::setOuterRadius));
            form.addRow("Inner radius (m):", meterSpinner(r.getInnerRadius(), 0.0, 0.5, 0.0005, r::setInnerRadius));
            form.addRow("Thickness (m):", meterSpinner(r.getThickness(), 0.0005, 0.05, 0.0005, r::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, r.getMaterial(), r::setMaterial));

        } else if (c instanceof Bulkhead b) {
            form.addRow("Outer radius (m):", meterSpinner(b.getOuterRadius(), 0.001, 0.5, 0.0005, b::setOuterRadius));
            form.addRow("Inner radius (m):", meterSpinner(b.getInnerRadius(), 0.0, 0.5, 0.0005, b::setInnerRadius));
            form.addRow("Thickness (m):", meterSpinner(b.getThickness(), 0.0005, 0.05, 0.0005, b::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, b.getMaterial(), b::setMaterial));

        } else if (c instanceof TubeCoupler tc) {
            form.addRow("Length (m):", meterSpinner(tc.getLength(), 0.001, 2.0, 0.001, tc::setLength));
            form.addRow("Outer radius (m):", meterSpinner(tc.getOuterRadius(), 0.001, 0.5, 0.0005, tc::setOuterRadius));
            form.addRow("Wall thickness (m):", meterSpinner(tc.getThickness(), 0.0001, 0.05, 0.0001, tc::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, tc.getMaterial(), tc::setMaterial));

        } else if (c instanceof EngineBlock eb) {
            form.addRow("Outer radius (m):", meterSpinner(eb.getOuterRadius(), 0.001, 0.2, 0.0005, eb::setOuterRadius));
            form.addRow("Inner radius (m):", meterSpinner(eb.getInnerRadius(), 0.0, 0.2, 0.0005, eb::setInnerRadius));
            form.addRow("Thickness (m):", meterSpinner(eb.getThickness(), 0.0005, 0.05, 0.0005, eb::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, eb.getMaterial(), eb::setMaterial));

        } else if (c instanceof LaunchLug lug) {
            form.addRow("Length (m):", meterSpinner(lug.getLength(), 0.001, 0.5, 0.001, lug::setLength));
            form.addRow("Outer radius (m):", meterSpinner(lug.getOuterRadius(), 0.0005, 0.05, 0.0005, lug::setOuterRadius));
            form.addRow("Wall thickness (m):", meterSpinner(lug.getThickness(), 0.0001, 0.02, 0.0001, lug::setThickness));
            form.addRow("Material:", materialRow(Material.Type.BULK, lug.getMaterial(), lug::setMaterial));

        } else if (c instanceof RailButton rb) {
            form.addRow("Outer diameter (m):", meterSpinner(rb.getOuterDiameter(), 0.001, 0.05, 0.0005, rb::setOuterDiameter));
            form.addRow("Inner diameter (m):", meterSpinner(rb.getInnerDiameter(), 0.001, 0.05, 0.0005, rb::setInnerDiameter));
            form.addRow("Total height (m):", meterSpinner(rb.getTotalHeight(), 0.001, 0.05, 0.0005, rb::setTotalHeight));
            form.addRow("Base height (m):", meterSpinner(rb.getBaseHeight(), 0.0005, 0.02, 0.0005, rb::setBaseHeight));

        } else if (c instanceof MassComponent mc) {
            form.addRow("Mass (kg):", kgSpinner(mc.getComponentMass(), 0.0, 50.0, 0.005, mc::setComponentMass));
            form.addRow("Length (m):", meterSpinner(mc.getLength(), 0.001, 1.0, 0.001, mc::setLength));
            form.addRow("Radius (m):", meterSpinner(mc.getRadius(), 0.0, 0.5, 0.001, mc::setRadius));
            JComboBox<MassComponent.MassComponentType> typeCombo = new JComboBox<>(MassComponent.MassComponentType.values());
            typeCombo.setSelectedItem(mc.getMassComponentType());
            typeCombo.addActionListener(e -> withUndo("Change purpose",
                    () -> mc.setMassComponentType((MassComponent.MassComponentType) typeCombo.getSelectedItem())));
            form.addRow("Purpose:", typeCombo);
            form.addRow("", hint("Use this for ballast, an altimeter, flight computer, deployment charge, " +
                    "tracker, payload, recovery hardware, or battery -- purpose is a label only and doesn't " +
                    "change the physics, but keeps the tree readable."));

        } else if (c instanceof Parachute p) {
            form.addRow("Diameter (m):", meterSpinner(p.getDiameter(), 0.05, 5.0, 0.01, p::setDiameter));
            form.addRow("Material:", materialRow(Material.Type.SURFACE, p.getMaterial(), p::setMaterial));
            form.addRow("Line count:", intSpinner(p.getLineCount(), 0, 24, p::setLineCount));
            form.addRow("Line length (m):", meterSpinner(p.getLineLength(), 0.0, 5.0, 0.01, p::setLineLength));
            form.addRow("Line material:", materialRow(Material.Type.LINE, p.getLineMaterial(), p::setLineMaterial));

        } else if (c instanceof Streamer s) {
            form.addRow("Strip length (m):", meterSpinner(s.getStripLength(), 0.01, 5.0, 0.01, s::setStripLength));
            form.addRow("Strip width (m):", meterSpinner(s.getStripWidth(), 0.005, 1.0, 0.005, s::setStripWidth));
            form.addRow("Material:", materialRow(Material.Type.SURFACE, s.getMaterial(), s::setMaterial));

        } else if (c instanceof ShockCord sc) {
            form.addRow("Cord length (m):", meterSpinner(sc.getCordLength(), 0.05, 10.0, 0.05, sc::setCordLength));
            form.addRow("Material:", materialRow(Material.Type.LINE, sc.getMaterial(), sc::setMaterial));

        } else if (c instanceof AxialStage) {
            form.addRow("", hint("A stage groups the components of one rocket stage (nose/body/fins/recovery/" +
                    "motor mount, etc). Select it and click \"Add Component\" to start attaching parts."));

        } else if (c instanceof Rocket) {
            form.addRow("", hint("This is the rocket root. Select it and click \"Add Component\" to add one " +
                    "or more Stages, then build out each stage."));

        } else {
            form.addRow("", hint("No editable properties for this component type yet -- use the OpenRocket " +
                    "desktop app for advanced tuning of this component if needed."));
        }

        JScrollPane scroll = new JScrollPane(form.panel());
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void showMotorPicker(RocketComponent mountComponent) {
        MotorMount mount = (MotorMount) mountComponent;
        double mountDiameterM = motorMountDiameterHint(mountComponent);

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Select Motor", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(820, 640);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(8, 8));

        ThrustCurveMotor[] chosen = new ThrustCurveMotor[1];

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JTextField queryField = new JTextField(16);
        JSpinner diamSpinner = new JSpinner(new SpinnerNumberModel(Math.round(mountDiameterM * 1000.0), 1.0, 500.0, 1.0));
        JCheckBox anyDiameterBox = new JCheckBox("Any diameter");
        filterRow.add(new JLabel("Search:"));
        filterRow.add(queryField);
        filterRow.add(new JLabel("Mount diameter (mm):"));
        filterRow.add(diamSpinner);
        filterRow.add(anyDiameterBox);

        DefaultTableModel tableModel = new DefaultTableModel(
                new Object[]{"Manufacturer", "Designation", "Common Name", "Dia (mm)", "Len (mm)", "Total Impulse (Ns)", "Type", "Case"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Bundled Thrust-Curve Database"));

        List<ThrustCurveMotorSet> currentResults = new ArrayList<>();

        DefaultTableModel customTableModel = new DefaultTableModel(
                new Object[]{"Manufacturer", "Designation", "Dia (mm)", "Len (mm)", "Total Impulse (Ns)", "Burn (s)", "Source"}, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        JTable customTable = new JTable(customTableModel);
        customTable.setAutoCreateRowSorter(true);
        customTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        JScrollPane customScroll = new JScrollPane(customTable);
        customScroll.setBorder(BorderFactory.createTitledBorder("Custom / Imported Motors (this session)"));

        List<ThrustCurveMotor> currentCustom = new ArrayList<>();

        Runnable refreshCustomTable = () -> {
            customTableModel.setRowCount(0);
            currentCustom.clear();
            currentCustom.addAll(RocketBuilderModel.customMotors());
            for (ThrustCurveMotor m : currentCustom) {
                customTableModel.addRow(new Object[]{
                        m.getManufacturer(), m.getDesignation(),
                        String.format("%.1f", m.getDiameter() * 1000.0),
                        String.format("%.1f", m.getLength() * 1000.0),
                        String.format("%.0f", m.getTotalImpulseEstimate()),
                        String.format("%.2f", m.getBurnTimeEstimate()),
                        m.getCaseInfo()
                });
            }
        };

        Runnable doSearch = () -> {
            tableModel.setRowCount(0);
            currentResults.clear();
            Double diamFilter = anyDiameterBox.isSelected() ? null : ((Number) diamSpinner.getValue()).doubleValue() / 1000.0;
            List<ThrustCurveMotorSet> results = model.searchMotorSets(queryField.getText(), diamFilter, 0.0015);
            currentResults.addAll(results);
            for (ThrustCurveMotorSet ms : results) {
                tableModel.addRow(new Object[]{
                        ms.getManufacturer(), ms.getDesignation(), ms.getCommonName(),
                        String.format("%.1f", ms.getDiameter() * 1000.0),
                        String.format("%.1f", ms.getLength() * 1000.0),
                        ms.getTotalImpulse(), ms.getType(), ms.getCaseInfo()
                });
            }
        };
        queryField.addActionListener(e -> doSearch.run());
        diamSpinner.addChangeListener(e -> doSearch.run());
        anyDiameterBox.addActionListener(e -> {
            diamSpinner.setEnabled(!anyDiameterBox.isSelected());
            doSearch.run();
        });
        JButton searchButton = new JButton("Search");
        searchButton.addActionListener(e -> doSearch.run());
        filterRow.add(searchButton);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0) return;
            customTable.clearSelection();
            int modelRow = table.convertRowIndexToModel(row);
            chosen[0] = currentResults.get(modelRow).getMotors().get(0);
        });
        customTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = customTable.getSelectedRow();
            if (row < 0) return;
            table.clearSelection();
            int modelRow = customTable.convertRowIndexToModel(row);
            chosen[0] = currentCustom.get(modelRow);
        });

        JPanel customButtonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton importButton = new JButton("Import Motor File (.eng/.rse)...");
        JButton createButton = new JButton("Create Custom Motor...");
        customButtonRow.add(importButton);
        customButtonRow.add(createButton);

        importButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(lastDir);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Motor thrust-curve files (*.eng, *.rse, *.zip)", "eng", "rse", "zip"));
            int result = chooser.showOpenDialog(dialog);
            if (result != JFileChooser.APPROVE_OPTION) return;
            File f = chooser.getSelectedFile();
            lastDir = f.getParentFile();
            try {
                List<ThrustCurveMotor> imported = RocketBuilderModel.importMotorFile(f);
                refreshCustomTable.run();
                JOptionPane.showMessageDialog(dialog, "Imported " + imported.size() + " motor(s) from " + f.getName() + ".");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Could not import motor file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        createButton.addActionListener(e -> {
            ThrustCurveMotor created = showCreateCustomMotorDialog(dialog, mountDiameterM);
            if (created != null) {
                refreshCustomTable.run();
            }
        });

        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton clearButton = new JButton("Clear Motor");
        JButton assignButton = new JButton("Assign Selected Motor");
        JButton cancelButton = new JButton("Cancel");
        bottomRow.add(clearButton);
        bottomRow.add(cancelButton);
        bottomRow.add(assignButton);

        assignButton.addActionListener(e -> {
            if (chosen[0] == null) {
                JOptionPane.showMessageDialog(dialog, "Select a motor row first (from either table).");
                return;
            }
            withUndo("Assign motor", () -> model.assignMotor(mount, chosen[0]));
            dialog.dispose();
            refreshSelectedPropertyPanel();
        });
        clearButton.addActionListener(e -> {
            withUndo("Clear motor", () -> model.clearMotor(mount));
            dialog.dispose();
            refreshSelectedPropertyPanel();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        JLabel note = new JLabel("<html><body style='width: 760px'><i>Diameter filter defaults to this " +
                "component's inner radius × 2 (the motor's actual OD, not the mount tube's OD). Widen the " +
                "tolerance or check \"Any diameter\" if nothing shows up. Custom/imported motors persist for " +
                "this running session only -- re-define or re-import them if you restart Arc-Sim and reopen " +
                "a rocket that uses one.</i></body></html>");
        note.setForeground(Color.GRAY);
        note.setBorder(new EmptyBorder(0, 6, 6, 6));

        JPanel top = new JPanel(new BorderLayout());
        top.add(filterRow, BorderLayout.NORTH);
        top.add(note, BorderLayout.SOUTH);

        JSplitPane tablesSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, customScroll);
        tablesSplit.setResizeWeight(0.62);
        tablesSplit.setOneTouchExpandable(true);
        tablesSplit.setContinuousLayout(true);

        JPanel south = new JPanel(new BorderLayout());
        south.add(customButtonRow, BorderLayout.NORTH);
        south.add(bottomRow, BorderLayout.SOUTH);

        dialog.add(top, BorderLayout.NORTH);
        dialog.add(tablesSplit, BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);

        doSearch.run();
        refreshCustomTable.run();
        dialog.setVisible(true);
    }

    private ThrustCurveMotor showCreateCustomMotorDialog(Window owner, double mountDiameterHintM) {
        JTextField designationField = new JTextField("Custom Motor 1");
        JTextField manufacturerField = new JTextField("Custom");
        JComboBox<Motor.Type> typeCombo = new JComboBox<>(Motor.Type.values());
        typeCombo.setSelectedItem(Motor.Type.SINGLE);
        JSpinner diamSpinner = new JSpinner(new SpinnerNumberModel(Math.max(1.0, Math.round(mountDiameterHintM * 1000.0)), 1.0, 500.0, 1.0));
        JSpinner lengthSpinner = new JSpinner(new SpinnerNumberModel(100.0, 1.0, 3000.0, 1.0));
        JSpinner burnTimeSpinner = new JSpinner(new SpinnerNumberModel(3.0, 0.05, 60.0, 0.1));
        JSpinner impulseSpinner = new JSpinner(new SpinnerNumberModel(100.0, 0.1, 100000.0, 5.0));
        JSpinner initialMassSpinner = new JSpinner(new SpinnerNumberModel(0.2, 0.001, 200.0, 0.01));
        JSpinner propellantMassSpinner = new JSpinner(new SpinnerNumberModel(0.1, 0.001, 199.0, 0.01));
        JTextField delaysField = new JTextField("7");
        JLabel avgThrustLabel = new JLabel();

        Runnable updateAvgThrust = () -> {
            double burn = ((Number) burnTimeSpinner.getValue()).doubleValue();
            double impulse = ((Number) impulseSpinner.getValue()).doubleValue();
            avgThrustLabel.setText(String.format("≈ average thrust: %.1f N (peak ≈ %.1f N on the idealized trapezoid)",
                    impulse / burn, impulse / (0.9 * burn)));
        };
        burnTimeSpinner.addChangeListener(e -> updateAvgThrust.run());
        impulseSpinner.addChangeListener(e -> updateAvgThrust.run());
        updateAvgThrust.run();

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        Object[][] fields = {
                {"Designation:", designationField},
                {"Manufacturer / team name:", manufacturerField},
                {"Motor type:", typeCombo},
                {"Diameter (mm):", diamSpinner},
                {"Length (mm):", lengthSpinner},
                {"Burn time (s):", burnTimeSpinner},
                {"Total impulse (Ns):", impulseSpinner},
                {"Initial (loaded) mass (kg):", initialMassSpinner},
                {"Propellant mass (kg):", propellantMassSpinner},
                {"Ejection delays (s, comma-separated):", delaysField},
        };
        for (Object[] f : fields) {
            c.gridx = 0;
            c.gridy = row;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(new JLabel((String) f[0]), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            panel.add((JComponent) f[1], c);
            row++;
        }
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        panel.add(avgThrustLabel, c);
        c.gridy = row;
        panel.add(hint("Builds an idealized trapezoidal thrust curve (10% ramp-up / 80% plateau / 10% " +
                "tail-off) that reproduces the total impulse and burn time you enter exactly -- a design-" +
                "stage estimate, not a certified thrust curve. For a real motor's actual data, use " +
                "\"Import Motor File\" instead. Persists for this running session only."), c);

        while (true) {
            int result = JOptionPane.showConfirmDialog(owner, panel, "Create Custom Motor",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return null;
            try {
                double[] delays;
                String delaysText = delaysField.getText().trim();
                if (delaysText.isEmpty()) {
                    delays = new double[]{0.0};
                } else {
                    String[] parts = delaysText.split(",");
                    delays = new double[parts.length];
                    for (int i = 0; i < parts.length; i++) delays[i] = Double.parseDouble(parts[i].trim());
                }
                return RocketBuilderModel.createCustomMotor(
                        designationField.getText(), manufacturerField.getText(),
                        (Motor.Type) typeCombo.getSelectedItem(),
                        ((Number) diamSpinner.getValue()).doubleValue() / 1000.0,
                        ((Number) lengthSpinner.getValue()).doubleValue() / 1000.0,
                        ((Number) burnTimeSpinner.getValue()).doubleValue(),
                        ((Number) impulseSpinner.getValue()).doubleValue(),
                        ((Number) initialMassSpinner.getValue()).doubleValue(),
                        ((Number) propellantMassSpinner.getValue()).doubleValue(),
                        delays);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(owner, "Could not create motor: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static class ComponentTreeModel implements TreeModel {
        private final Rocket root;
        private final List<TreeModelListener> listeners = new ArrayList<>();

        ComponentTreeModel(Rocket root) {
            this.root = root;
        }

        @Override
        public Object getRoot() {
            return root;
        }

        @Override
        public Object getChild(Object parent, int index) {
            return ((RocketComponent) parent).getChild(index);
        }

        @Override
        public int getChildCount(Object parent) {
            return ((RocketComponent) parent).getChildCount();
        }

        @Override
        public boolean isLeaf(Object node) {
            return ((RocketComponent) node).getChildCount() == 0;
        }

        @Override
        public void valueForPathChanged(TreePath path, Object newValue) {
        }

        @Override
        public int getIndexOfChild(Object parent, Object child) {
            if (parent == null || child == null) return -1;
            return ((RocketComponent) parent).getChildPosition((RocketComponent) child);
        }

        @Override
        public void addTreeModelListener(TreeModelListener l) {
            listeners.add(l);
        }

        @Override
        public void removeTreeModelListener(TreeModelListener l) {
            listeners.remove(l);
        }

        private Object[] chainFrom(RocketComponent node) {
            List<RocketComponent> chain = new ArrayList<>();
            RocketComponent cur = node;
            while (cur != null) {
                chain.add(0, cur);
                cur = cur.getParent();
            }
            return chain.toArray();
        }

        void fireStructureChanged(RocketComponent from) {
            TreeModelEvent evt = new TreeModelEvent(this, chainFrom(from));
            for (TreeModelListener l : new ArrayList<>(listeners)) l.treeStructureChanged(evt);
        }

        void fireNodeChanged(RocketComponent node) {
            RocketComponent parent = node.getParent();
            if (parent == null) {
                fireStructureChanged(node);
                return;
            }
            int idx = parent.getChildPosition(node);
            TreeModelEvent evt = new TreeModelEvent(this, chainFrom(parent), new int[]{idx}, new Object[]{node});
            for (TreeModelListener l : new ArrayList<>(listeners)) l.treeNodesChanged(evt);
        }
    }

    private static class ComponentTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                        boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof RocketComponent c) {
                String name = c.getName();
                if (name == null || name.isBlank()) name = c.getClass().getSimpleName();
                setText(name);
            }
            return this;
        }
    }

    private static class PropForm {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row = 0;

        JPanel panel() {
            return panel;
        }

        void addRow(String label, JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4);
            c.gridx = 0;
            c.gridy = row;
            c.anchor = GridBagConstraints.WEST;
            panel.add(new JLabel(label), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            panel.add(field, c);
            row++;
        }

        void addSection(String title) {
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(12, 4, 4, 4);
            c.gridx = 0;
            c.gridy = row;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.WEST;
            JLabel l = new JLabel(title);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            panel.add(l, c);
            row++;
        }
    }
}
