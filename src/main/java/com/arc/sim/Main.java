package com.arc.sim;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }
        String mode = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (mode) {
            case "fullsweep":
                FullFactorialSweep.main(rest);
                break;
            case "design":
                DesignSolver.main(rest);
                break;
            default:
                printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  Engine 1 (TRUE full-factorial sweep, every combination, rocket held fixed):");
        System.out.println("    java -jar arc-sim.jar fullsweep <input.ork> <sweep_grid.properties> [outputDir] [--force]");
        System.out.println();
        System.out.println("  Engine 2  (solve ballast + fin height + fin sweep for one fixed atmosphere):");
        System.out.println("    java -jar arc-sim.jar design <input.ork> <targetApogeeM> <targetTimeMinS> <targetTimeMaxS> \\");
        System.out.println("        <site> <windAvgMs> <windStdDevMs> <turbulencePct> <windDirDeg> <tempC> <pressureMbar>");
        System.out.println();
        System.out.println("  Engine 3 (weather-driven design), the Geometry Exporter, and the Rocket Builder are GUI-only -- no CLI command.");
        System.out.println();
        System.out.println("  site = MDRA_SOD_FARM | SPAAR_LANCASTER");
        System.out.println();
        System.out.println("  outputDir is optional -- defaults to the input .ork's own folder.");
        System.out.println("  All output filenames/foldernames are auto-generated as <orkName>_<simType>_<timestamp>");
        System.out.println("  so repeated runs never overwrite a previous result. Engine 1 writes .parquet (not");
        System.out.println("  .xlsx) plus a companion _summary.csv; both are readable in this toolkit's GUI");
        System.out.println("  \"Data Viewer\" tab (also opens plain .xlsx/.csv).");
    }
}

