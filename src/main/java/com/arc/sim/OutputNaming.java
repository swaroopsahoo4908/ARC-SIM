package com.arc.sim;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Central utility for generating output file and folder names across all engines, enforcing two
 * invariants:
 *   1. Every generated file is self-describing: "<orkBaseName>_<simType>_<timestamp>.<ext>"
 *   2. No output is ever silently overwritten. If the exact name is already in use (e.g., two
 *      runs initiated within the same second), a "_2", "_3", ... suffix is appended until an
 *      unused name is found.
 *
 * Used by FullFactorialSweep (simType "fullfactorial"), DesignSolver ("solved"), MeshExporter
 * ("geometry"), and WeatherDrivenDesign ("weatherdesign", for the subfolder name).
 */
public class OutputNaming {

    // Default output folder names, one per engine, each created as a sibling of the .ork file
    // being processed rather than at a fixed project-root location, so outputs remain co-located
    // with their source design file regardless of its location. Used as the GUI's default
    // "Output folder" when that field is left blank; an explicit path overrides this default.
    public static final String FULL_FACTORIAL_FOLDER = "Full Factorial";
    public static final String OPENROCKET_SOLVES_FOLDER = "OpenRocket Solves";
    public static final String CAD_FILES_FOLDER = "CAD Files";
    public static final String ENGINE_4_FOLDER = "Engine 4";

    // yyyyMMdd_HHmmss provides 1-second resolution; the collision-avoidance loop below handles
    // same-second reruns. Not shared as a static SimpleDateFormat instance because SimpleDateFormat
    // is not thread-safe, and FullFactorialSweep naming may be invoked from both GUI and worker
    // threads.
    private static SimpleDateFormat newFormat() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss");
    }

    /** Strips the extension from a filename, e.g., "WYVERN_E4.ork" yields "WYVERN_E4". */
    public static String baseName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String timestamp() {
        return newFormat().format(new Date());
    }

    /**
     * Builds a collision-safe "<orkBase>_<simType>_<timestamp>.<ext>" file path inside outDir
     * (created if it does not already exist). If outDir is null, uses the ork file's parent
     * directory (or "." if the ork file has no parent, e.g., a bare filename).
     * Never returns a path that already exists.
     */
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

    /**
     * Builds a collision-safe "<orkBase>_<simType>_<timestamp>" subfolder inside outDir (created
     * if it does not already exist), for engines that emit multiple files per run (e.g., batch
     * .ork generation). The returned directory is created before being returned. If outDir is
     * null, uses the ork file's parent directory.
     */
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
     * Resolves (creating if necessary) a folder with a fixed name, located next to orkFile, e.g.,
     * "Full Factorial" or "OpenRocket Solves". This is the default output location for each engine
     * when the GUI's "Output folder" field is left blank; an explicit path bypasses this behavior
     * entirely. If orkFile has no parent (a bare filename with no directory component), the folder
     * is created in the current working directory instead.
     */
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
