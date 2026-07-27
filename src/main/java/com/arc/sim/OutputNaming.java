package com.arc.sim;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class OutputNaming {

    public static final String FULL_FACTORIAL_FOLDER = "Full Factorial";
    public static final String OPENROCKET_SOLVES_FOLDER = "OpenRocket Solves";
    public static final String CAD_FILES_FOLDER = "CAD Files";
    public static final String ENGINE_4_FOLDER = "Engine 4";
    public static final String ROCKET_BUILDER_FOLDER = "Rocket Builder";
    public static final String OPENROCKET_ROOT_FOLDER = "OpenRocket";

    private static SimpleDateFormat newFormat() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss");
    }

    public static String baseName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String timestamp() {
        return newFormat().format(new Date());
    }

    public static File uniqueFile(File orkFile, File outDir, String simType, String ext) {
        File dir = resolveDir(orkFile, outDir);
        String base = baseName(orkFile) + "_" + simType + "_" + timestamp();
        File out = new File(dir, base + "." + ext);
        int suffix = 2;
        while (out.exists()) {
            out = new File(dir, base + "_" + suffix + "." + ext);
            suffix++;
        }
        return out;
    }

    public static File uniqueDir(File orkFile, File outDir, String simType) {
        File parent = resolveDir(orkFile, outDir);
        String base = baseName(orkFile) + "_" + simType + "_" + timestamp();
        File dir = new File(parent, base);
        int suffix = 2;
        while (dir.exists()) {
            dir = new File(parent, base + "_" + suffix);
            suffix++;
        }
        if (!dir.mkdirs()) {
            throw new IllegalStateException("Could not create output folder: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * Resolves (creating if needed) an app-relative "OpenRocket/&lt;folderName&gt;" category folder next
     * to the app itself, mirroring the input-file-relative namedSubfolder() convention the other engines
     * use for tools (like Rocket Builder) that don't start from an existing .ork input file. Falls back
     * to appDir itself if creation fails for any reason (read-only install location, etc).
     */
    public static File appRelativeFolder(File appDir, String folderName) {
        File root = new File(appDir, OPENROCKET_ROOT_FOLDER);
        File dir = new File(root, folderName);
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            return appDir;
        }
        return dir;
    }

    public static File namedSubfolder(File orkFile, String folderName) {
        File parent = orkFile.getParentFile();
        if (parent == null) parent = new File(".");
        File dir = new File(parent, folderName);
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Could not create output folder: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static File resolveDir(File orkFile, File outDir) {
        File dir = outDir;
        if (dir == null) {
            dir = orkFile.getParentFile();
        }
        if (dir == null) {
            dir = new File(".");
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Could not create output folder: " + dir.getAbsolutePath());
        }
        return dir;
    }
}

