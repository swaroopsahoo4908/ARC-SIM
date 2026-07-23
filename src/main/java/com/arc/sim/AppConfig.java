package com.arc.sim;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Per-user application configuration.
 *
 * Component specification:
 * - Purpose: Persists the one setting that differs from user to user (the weather API key) so
 *   that a single distributed build of this toolkit is immediately usable by anyone, rather than
 *   assuming the original developer's personal API credentials. Previously this was compiled
 *   directly into ArcSimGui as a constant -- workable only on the original developer's machine.
 * - Storage location: a dotfile next to the running jar/launcher ("<appDir>/.arc-sim-config.properties"),
 *   not the user's home directory. This keeps the whole distribution self-contained -- the folder
 *   holding the jar/launchers/config is everything the app touches; nothing is written outside it,
 *   so the folder can be freely moved, copied, or zipped back up without leaving orphaned state
 *   elsewhere on the machine. See appDir() for how that folder is located at runtime.
 * - Working folder: intentionally NOT a persisted setting. ArcSimGui always starts every file
 *   chooser at appDir() -- the folder containing ArcSim.jar/ArcSim.command -- each launch, and
 *   remembers whatever folder you browse to for the rest of that session (in-memory only, via
 *   ArcSimGui's lastDir), so the app always starts where you opened it from and keeps up with you
 *   from there without needing a separate folder preference to configure or drift out of sync.
 * - First-run detection: firstRunComplete is false until the setup wizard (see ArcSimGui) has been
 *   completed once; ArcSimGui checks this flag at startup and shows the wizard when false.
 */
public class AppConfig {

    private static final File APP_DIR = resolveAppDir();
    private static final File CONFIG_FILE = new File(APP_DIR, ".arc-sim-config.properties");

    public String weatherApiKey = "";
    public boolean firstRunComplete = false;

    private static AppConfig instance;

    /** Returns the process-wide singleton, loading from disk on first access. */
    public static synchronized AppConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Resolves the folder containing the running jar (or, when run from unpacked classes during
     * development, the current working directory) -- i.e. "the folder ArcSim.command was opened
     * from". Every file this app writes on its own initiative (its config file, and the folder
     * every file chooser starts in at launch) is anchored under this directory.
     */
    public static File appDir() {
        return APP_DIR;
    }

    private static File resolveAppDir() {
        try {
            File location = new File(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // location is the jar file itself when packaged, or a "classes" build-output directory
            // during development; either way, its parent (for the jar case) or itself (for the
            // directory case) is the folder we want to anchor to.
            File dir = location.isFile() ? location.getParentFile() : location;
            if (dir != null && dir.isDirectory()) return dir;
        } catch (Exception ignored) {
            // Falls through to the working-directory fallback below.
        }
        return new File(System.getProperty("user.dir"));
    }

    private static AppConfig load() {
        AppConfig cfg = new AppConfig();
        File source = CONFIG_FILE;
        boolean migrating = false;
        if (!source.isFile()) {
            // One-time migration: earlier builds stored config under the user's home directory
            // rather than next to the app. If that legacy file exists and the new self-contained
            // one doesn't yet, read from it so an already-configured user isn't sent back through
            // the first-run wizard; the migrated copy is written to the new location below.
            File legacy = new File(new File(System.getProperty("user.home"), ".arc-sim"), "config.properties");
            if (legacy.isFile()) {
                source = legacy;
                migrating = true;
            }
        }
        if (source.isFile()) {
            try (FileInputStream in = new FileInputStream(source)) {
                Properties p = new Properties();
                p.load(in);
                cfg.weatherApiKey = p.getProperty("weatherApiKey", cfg.weatherApiKey);
                cfg.firstRunComplete = Boolean.parseBoolean(p.getProperty("firstRunComplete", "false"));
                if (migrating) {
                    cfg.save(); // Write the migrated copy to the new self-contained location.
                    //noinspection ResultOfMethodCallIgnored
                    source.delete(); // Best-effort cleanup of the old per-user location; harmless if it fails.
                }
            } catch (Exception e) {
                System.err.println("Warning: failed to read " + source + " (" + e.getMessage() +
                        "); falling back to defaults.");
            }
        }
        return cfg;
    }

    /** Persists the current field values to disk, next to the app. */
    public void save() {
        try {
            Properties p = new Properties();
            p.setProperty("weatherApiKey", weatherApiKey == null ? "" : weatherApiKey);
            p.setProperty("firstRunComplete", String.valueOf(firstRunComplete));
            try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
                p.store(out, "arc-sim configuration -- safe to hand-edit or delete (deleting re-triggers " +
                        "the first-run setup wizard). Kept next to the app so the whole folder stays " +
                        "self-contained and portable.");
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to save " + CONFIG_FILE + ": " + e.getMessage());
        }
    }
}
