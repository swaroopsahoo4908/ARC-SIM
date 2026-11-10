package com.arc.sim;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which geometry the 3D composer and the mesh exporters use: the bundled ARC V2 CAD model, or the
 * generated body-of-revolution mesh. One shared setting, so every tab's toggle stays in step.
 */
public final class MeshSource {

    public static final String CONFIG_KEY = "mesh.source";
    private static final String CAD = "cad";
    private static final String GENERATED = "generated";

    private static volatile Boolean cad;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private MeshSource() {
    }

    /** True when the CAD model is preferred. Defaults to true, so the CAD is the base template. */
    public static boolean useCad() {
        Boolean v = cad;
        if (v == null) {
            String stored;
            try {
                stored = AppConfig.get().getField(CONFIG_KEY, CAD);
            } catch (Throwable t) {
                stored = CAD;
            }
            v = !GENERATED.equals(stored);
            cad = v;
        }
        return v;
    }

    /** Sets the preference, persists it (best effort) and tells every listener. */
    public static void setUseCad(boolean useCad) {
        if (cad != null && cad == useCad) return;
        cad = useCad;
        try {
            AppConfig cfg = AppConfig.get();
            cfg.setField(CONFIG_KEY, useCad ? CAD : GENERATED);
            cfg.save();
        } catch (Throwable ignored) {
        }
        for (Runnable r : LISTENERS) r.run();
    }

    /** In-memory only; used by tests and headless runs that must not touch the config file. */
    public static void setUseCadTransient(boolean useCad) {
        cad = useCad;
    }

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }
}
