package com.arc.sim;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The filament the ARC V2 airframe is printed in. The mass is the weighed total of the printed parts (nose, upper,
 * mid, lower, motor mount, retainer, four fins); the per-part masses scale from the CAD volumes to match it.
 *
 * <p>PLA Basic: 306 g weighed. PLA Aero: 180.6 g weighed (ratio 0.590, effective density 0.673 g/cm3, inside the
 * 0.55 to 1.05 g/cm3 print-density range Bambu Lab lists for Aero, TDS V4.0, filament 1.21 g/cm3).
 */
public enum PrintMaterial {
    BASIC("basic", "PLA Basic", 306.0),
    AERO("aero", "PLA Aero", 180.6);

    public static final String CONFIG_KEY = "print.material";
    private static volatile PrintMaterial current;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    public final String id;
    public final String label;
    public final double printedMassG;

    PrintMaterial(String id, String label, double printedMassG) {
        this.id = id;
        this.label = label;
        this.printedMassG = printedMassG;
    }

    public String templateResource() {
        return this == AERO ? "/cad/ARC_V2_template_aero.ork" : "/cad/ARC_V2_template.ork";
    }

    public String templateFileName() {
        return this == AERO ? "ARC_V2_template_aero.ork" : "ARC_V2_template.ork";
    }

    @Override
    public String toString() {
        return label + " (" + printedMassG + " g)";
    }

    public static PrintMaterial fromId(String id) {
        for (PrintMaterial m : values()) if (m.id.equalsIgnoreCase(id) || m.label.equalsIgnoreCase(id)) return m;
        return BASIC;
    }

    public static PrintMaterial current() {
        PrintMaterial m = current;
        if (m == null) {
            String stored;
            try {
                stored = AppConfig.get().getField(CONFIG_KEY, BASIC.id);
            } catch (Throwable t) {
                stored = BASIC.id;
            }
            m = fromId(stored);
            current = m;
        }
        return m;
    }

    public static void setCurrent(PrintMaterial m) {
        if (current == m) return;
        current = m;
        try {
            AppConfig cfg = AppConfig.get();
            cfg.setField(CONFIG_KEY, m.id);
            cfg.save();
        } catch (Throwable ignored) {
        }
        for (Runnable r : LISTENERS) r.run();
    }

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }
}
