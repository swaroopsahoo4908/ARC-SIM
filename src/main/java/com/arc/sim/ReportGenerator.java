package com.arc.sim;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a plain PDF summary report from a completed Engine 1 (Full Factorial Sweep) or Engine 4
 * (Weather-Driven Design) run -- headline numbers, best/matching results, and a stability-margin reminder,
 * suitable for dropping into a competition binder or sharing without needing to open the raw data files.
 */
public class ReportGenerator {

    private static final int MAX_SCAN_ROWS = 200_000;
    private static final int MAX_TABLE_ROWS = 15;

    public static File generateFullFactorialReport(File parquetFile, File outputPdf) throws Exception {
        File summaryFile = new File(parquetFile.getParentFile(), OutputNaming.baseName(parquetFile) + "_summary.csv");
        Map<String, String> summary = new LinkedHashMap<>();
        if (summaryFile.exists()) {
            CsvUtil.Table t = CsvUtil.read(summaryFile);
            for (List<String> row : t.rows) {
                if (row.size() >= 2) summary.put(row.get(0), row.get(1));
            }
        }

        MiniParquet.ReadResult result = MiniParquet.read(parquetFile, MAX_SCAN_ROWS);
        int meetsBothCol = indexOf(result.columnNames, "meets_both");
        int apogeeCol = indexOf(result.columnNames, "apogee_m");
        int timeCol = indexOf(result.columnNames, "flight_time_s");
        int windAvgCol = indexOf(result.columnNames, "wind_avg_ms");
        int windDirCol = indexOf(result.columnNames, "wind_dir_deg");
        int tempCol = indexOf(result.columnNames, "temp_c");
        int pressureCol = indexOf(result.columnNames, "pressure_mbar");
        int rodCol = indexOf(result.columnNames, "rod_angle_deg");
        int siteCol = indexOf(result.columnNames, "site");

        List<Object[]> matches = new ArrayList<>();
        if (meetsBothCol >= 0) {
            for (Object[] row : result.rows) {
                if (Boolean.TRUE.equals(row[meetsBothCol]) || "true".equalsIgnoreCase(String.valueOf(row[meetsBothCol]))) {
                    matches.add(row);
                    if (matches.size() >= MAX_TABLE_ROWS) break;
                }
            }
        }

        MiniPdf.Writer w = new MiniPdf.Writer();
        w.title("Arc-Sim -- Full Factorial Sweep Report");
        w.body("Source: " + parquetFile.getName());
        w.body("Generated: " + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        w.blank();

        w.heading("Run Summary");
        for (Map.Entry<String, String> e : summary.entrySet()) {
            w.body(padLabel(e.getKey()) + wrap(e.getValue(), 70));
        }
        w.blank();

        w.heading("Conditions Meeting Both Targets" +
                (matches.isEmpty() ? "" : " (first " + matches.size() + " found, scanned up to " +
                        String.format("%,d", MAX_SCAN_ROWS) + " rows)"));
        if (meetsBothCol < 0) {
            w.body("(parquet file doesn't have a meets_both column -- unrecognized format)");
        } else if (matches.isEmpty()) {
            w.body("None found within the first " + String.format("%,d", MAX_SCAN_ROWS) +
                    " rows scanned -- see the full data in the Data Viewer tab for the complete picture.");
        } else {
            w.body(String.format("%-9s %-8s %-8s %-8s %-9s %-6s %-8s %-9s", "wind m/s", "dir deg", "temp C",
                    "press mb", "rod deg", "site", "apogee m", "time s"));
            for (Object[] row : matches) {
                w.body(String.format("%-9s %-8s %-8s %-9s %-9s %-6s %-8s %-9s",
                        fmt(row, windAvgCol), fmt(row, windDirCol), fmt(row, tempCol), fmt(row, pressureCol),
                        fmt(row, rodCol), fmtSite(row, siteCol), fmt(row, apogeeCol), fmt(row, timeCol)));
            }
        }
        w.blank();

        w.heading("Notes");
        w.body("This report reflects the .parquet/.csv output at generation time -- re-run Engine 1 and");
        w.body("regenerate this report if the grid, rocket, or targets change.");
        w.body("Always verify the stability margin (1-2 calibers) of any promising design in OpenRocket");
        w.body("directly before committing to it -- this sweep only reports apogee/flight-time, not stability.");

        w.write(outputPdf);
        return outputPdf;
    }

    public static File generateWeatherDesignReport(WeatherDrivenDesign.Result result, WeatherClient.Reading weather,
                                                     double targetApogeeM, double targetTimeMinS, double targetTimeMaxS,
                                                     File outputPdf) throws Exception {
        MiniPdf.Writer w = new MiniPdf.Writer();
        w.title("Arc-Sim -- Weather-Driven Design Report");
        w.body("Generated: " + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        w.body("Run folder: " + (result.runDir != null ? result.runDir.getName() : "n/a"));
        w.blank();

        w.heading("Weather Used");
        if (weather != null) {
            w.body(padLabel("Location:") + weather.locationName);
            w.body(padLabel("Fetched:") + weather.formattedFetchTime());
            w.body(padLabel("Wind avg / gust:") + String.format("%.2f / %.2f m/s", weather.windAvgMs, weather.windGustMs));
            w.body(padLabel("Direction:") + String.format("%.0f deg", weather.windDirDeg));
            w.body(padLabel("Temp / Pressure:") + String.format("%.1f C / %.1f mbar", weather.tempC, weather.pressureMbar));
            w.body(padLabel("Condition:") + weather.conditionText);
        }
        w.blank();

        w.heading("Targets");
        w.body(padLabel("Apogee:") + String.format("%.2f m", targetApogeeM));
        w.body(padLabel("Flight time:") + String.format("%.1f - %.1f s", targetTimeMinS, targetTimeMaxS));
        w.blank();

        w.heading("Main Solved Design");
        DesignSolver.Result main = result.mainSolve;
        if (main == null) {
            w.body("Main solve did not complete (cancelled before any pass finished).");
        } else {
            w.body(padLabel("Ballast:") + String.format("%.1f g", main.ballastKg * 1000));
            w.body(padLabel("Fin height:") + String.format("%.4f m", main.finHeightM));
            w.body(padLabel("Fin sweep:") + String.format("%.4f m (unchanged)", main.fixedSweepM));
            w.body(padLabel("Hole radius:") + String.format("%.2f in", main.holeRadiusM / 0.0254));
            w.body(padLabel("Apogee:") + String.format("%.2f m (%s)", main.flightResult.apogeeM,
                    main.apogeeOk ? "within tolerance" : "OUTSIDE tolerance"));
            w.body(padLabel("Flight time:") + String.format("%.2f s (%s)", main.flightResult.flightTimeS,
                    main.timeOk ? "within tolerance" : "OUTSIDE tolerance"));
            if (result.mainSolveWidenAttempts > 0) {
                w.body(padLabel("Note:") + "Bounds auto-widened " + result.mainSolveWidenAttempts + " time(s) to reach this design.");
            }
        }
        w.blank();

        w.heading("Margin Fin Sets (day-of wind variability, ballast/hole radius held fixed)");
        if (result.marginFins.isEmpty()) {
            w.body("None solved (run may have been cancelled before this stage).");
        } else {
            w.body(String.format("%-12s %-12s %-10s %-10s", "wind m/s", "fin height m", "apogee m", "time s"));
            for (WeatherDrivenDesign.MarginFin mf : result.marginFins) {
                w.body(String.format("%-12.2f %-12.4f %-10.2f %-10.2f",
                        mf.windSpeedMs, mf.finHeightM, mf.flightResult.apogeeM, mf.flightResult.flightTimeS));
            }
        }
        w.blank();

        w.heading("Notes");
        w.body("Always verify the stability margin (1-2 calibers) of the solved design in OpenRocket directly");
        w.body("before committing to it -- this solver trims fin height/sweep, which can move the CP.");
        w.body("Local-conditions sweep results (day-of variability) are in the companion .xlsx in the run folder.");

        w.write(outputPdf);
        return outputPdf;
    }

    private static int indexOf(List<String> names, String target) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(target)) return i;
        }
        return -1;
    }

    private static String fmt(Object[] row, int col) {
        if (col < 0 || col >= row.length || row[col] == null) return "-";
        Object v = row[col];
        if (v instanceof Double) return String.format("%.2f", (Double) v);
        return String.valueOf(v);
    }

    private static String fmtSite(Object[] row, int col) {
        if (col < 0 || col >= row.length || row[col] == null) return "-";
        String s = String.valueOf(row[col]);
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    private static String padLabel(String label) {
        StringBuilder sb = new StringBuilder(label);
        while (sb.length() < 20) sb.append(' ');
        return sb.toString();
    }

    private static String wrap(String value, int maxLen) {
        return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
    }
}
