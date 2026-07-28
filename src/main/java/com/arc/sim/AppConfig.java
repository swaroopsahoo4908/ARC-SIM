package com.arc.sim;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class AppConfig {

    private static final File APP_DIR = resolveAppDir();
    private static final File CONFIG_FILE = new File(APP_DIR, ".arc-sim-config.properties");
    private static final String FIELD_PREFIX = "field.";

    public String weatherApiKey = "";
    public boolean firstRunComplete = false;

    public final Map<String, String> fields = new LinkedHashMap<>();

    public String getField(String key, String defaultValue) {
        return fields.getOrDefault(key, defaultValue);
    }

    public void setField(String key, String value) {
        if (value == null) {
            fields.remove(key);
        } else {
            fields.put(key, value);
        }
    }

    private static AppConfig instance;

    public static synchronized AppConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static File appDir() {
        return APP_DIR;
    }

    private static File resolveAppDir() {
        try {
            File jarLocation = new File(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = jarLocation.isFile() ? jarLocation.getParentFile() : jarLocation;
            if (dir != null && dir.isDirectory()) return dir;
        } catch (Exception ignored) {
        }
        return new File(System.getProperty("user.dir"));
    }

    private static AppConfig load() {
        AppConfig cfg = new AppConfig();
        File source = CONFIG_FILE;
        boolean migrating = false;
        if (!source.isFile()) {
            File legacy = new File(new File(System.getProperty("user.home"), ".arc-sim"), "config.properties");
            if (legacy.isFile()) {
                source = legacy;
                migrating = true;
            }
        }
        if (source.isFile()) {
            try (FileInputStream in = new FileInputStream(source)) {
                Properties props = new Properties();
                props.load(in);
                cfg.weatherApiKey = props.getProperty("weatherApiKey", cfg.weatherApiKey);
                cfg.firstRunComplete = Boolean.parseBoolean(props.getProperty("firstRunComplete", "false"));
                for (String name : props.stringPropertyNames()) {
                    if (name.startsWith(FIELD_PREFIX)) {
                        cfg.fields.put(name.substring(FIELD_PREFIX.length()), props.getProperty(name));
                    }
                }
                if (migrating) {
                    cfg.save();
                    source.delete();
                }
            } catch (Exception e) {
                System.err.println("Warning: failed to read " + source + " (" + e.getMessage() +
                        "); falling back to defaults.");
            }
        }
        return cfg;
    }

    public void save() {
        try {
            Properties props = new Properties();
            props.setProperty("weatherApiKey", weatherApiKey == null ? "" : weatherApiKey);
            props.setProperty("firstRunComplete", String.valueOf(firstRunComplete));
            for (Map.Entry<String, String> e : fields.entrySet()) {
                props.setProperty(FIELD_PREFIX + e.getKey(), e.getValue());
            }
            try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
                props.store(out, "arc-sim configuration -- safe to hand-edit or delete (deleting re-triggers " +
                        "the first-run setup wizard). Kept next to the app so the whole folder stays " +
                        "self-contained and portable.");
            }
        } catch (Exception e) {
            System.err.println("Warning: failed to save " + CONFIG_FILE + ": " + e.getMessage());
        }
    }
}

