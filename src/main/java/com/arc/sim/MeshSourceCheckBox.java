package com.arc.sim;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

/** A checkbox bound to the shared {@link MeshSource} setting, so every generator and preview toggle stays in step. */
public final class MeshSourceCheckBox extends JCheckBox {

    private final Runnable listener = () -> SwingUtilities.invokeLater(() -> setSelected(MeshSource.useCad()));

    public MeshSourceCheckBox(String text) {
        super(text, MeshSource.useCad());
        setToolTipText("Checked: export the ARC V2 CAD geometry when the rocket is the ARC V2 airframe. "
                + "Unchecked: export the generated body-of-revolution mesh.");
        addActionListener(e -> MeshSource.setUseCad(isSelected()));
    }

    @Override
    public void addNotify() {
        super.addNotify();
        MeshSource.addListener(listener);
        setSelected(MeshSource.useCad());
    }

    @Override
    public void removeNotify() {
        MeshSource.removeListener(listener);
        super.removeNotify();
    }
}
