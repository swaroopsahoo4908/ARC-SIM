package com.arc.sim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DeviceLocation {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final String WEATHERAPI_IP_LOOKUP_URL_BASE = "https://api.weatherapi.com/v1/ip.json?q=auto:ip&key=";
    private static final String IP_GEOLOCATION_FALLBACK_URL = "https://ipapi.co/json/";
    private static final String ELEVATION_URL_BASE = "https://api.open-elevation.com/api/v1/lookup?locations=";
    private static final int CORE_LOCATION_CLI_TIMEOUT_S = 12;

    public static class Reading {
        public final double latitudeDeg;
        public final double longitudeDeg;
        public final double altitudeM;
        public final String source;
        public final String accuracyNote;

        public final double horizontalAccuracyM;

        Reading(double latitudeDeg, double longitudeDeg, double altitudeM, String source, String accuracyNote) {
            this(latitudeDeg, longitudeDeg, altitudeM, source, accuracyNote, Double.NaN);
        }

        Reading(double latitudeDeg, double longitudeDeg, double altitudeM, String source, String accuracyNote,
                double horizontalAccuracyM) {
            this.latitudeDeg = latitudeDeg;
            this.longitudeDeg = longitudeDeg;
            this.altitudeM = altitudeM;
            this.source = source;
            this.accuracyNote = accuracyNote;
            this.horizontalAccuracyM = horizontalAccuracyM;
        }
    }

    public static Reading fetch(String weatherApiKey) throws Exception {
        Reading onDevice = tryCoreLocationCli();
        if (onDevice != null) return onDevice;
        try {
            return fetchViaWeatherApiIpLookup(weatherApiKey);
        } catch (Exception primaryFailure) {
            return fetchViaIpApiCoFallback();
        }
    }

    private static Reading tryCoreLocationCli() {
        Process proc;
        try {
            ProcessBuilder pb = new ProcessBuilder("CoreLocationCLI", "--json");
            pb.redirectErrorStream(true);
            proc = pb.start();
        } catch (Exception e) {
            System.err.println("CoreLocationCLI not found on PATH -- falling back to IP-based geolocation.");
            return null;
        }

        String rawOutput;
        try {
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line);
            }
            rawOutput = out.toString();

            boolean finished = proc.waitFor(CORE_LOCATION_CLI_TIMEOUT_S, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                System.err.println("CoreLocationCLI did not respond within " + CORE_LOCATION_CLI_TIMEOUT_S
                        + "s -- falling back to IP-based geolocation.");
                return null;
            }
            if (proc.exitValue() != 0) {
                System.err.println("CoreLocationCLI exited with code " + proc.exitValue()
                        + " -- falling back to IP-based geolocation. Output: " + rawOutput);
                return null;
            }
        } catch (Exception e) {
            System.err.println("CoreLocationCLI invocation failed (" + e.getMessage() + ") -- falling back to IP-based geolocation.");
            return null;
        }

        try {
            Object root = MiniJson.parse(rawOutput);
            double lat = asFlexibleDouble(MiniJson.get(root, "latitude"));
            double lon = asFlexibleDouble(MiniJson.get(root, "longitude"));
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                System.err.println("CoreLocationCLI output did not include a usable latitude/longitude -- "
                        + "falling back to IP-based geolocation. Raw output: " + rawOutput);
                return null;
            }
            double alt = asFlexibleDouble(MiniJson.get(root, "altitude"));
            if (Double.isNaN(alt)) alt = fetchElevation(lat, lon);
            double hAccuracy = asFlexibleDouble(MiniJson.get(root, "h_accuracy"));

            String note = "Resolved via this Mac's Wi-Fi Positioning System fix (Macs have no GPS receiver; "
                    + "accuracy depends on how densely this area's Wi-Fi access points have been mapped, and can be "
                    + "off by a mile or more in low-density/rural areas even on a successful fix). "
                    + (Double.isNaN(hAccuracy) ? "No horizontal-accuracy figure was reported for this fix. "
                            : String.format("CoreLocation reports a horizontal accuracy radius of %.0f m for this fix. ", hAccuracy))
                    + "Confirm against a handheld GPS reading before use in a flight-critical calculation.";

            return new Reading(lat, lon, alt, "CoreLocationCLI (on-device Wi-Fi positioning)", note, hAccuracy);
        } catch (Exception e) {
            System.err.println("Could not parse CoreLocationCLI output (" + e.getMessage()
                    + ") -- falling back to IP-based geolocation. Raw output: " + rawOutput);
            return null;
        }
    }

    private static double asFlexibleDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Reading fetchViaWeatherApiIpLookup(String weatherApiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(WEATHERAPI_IP_LOOKUP_URL_BASE + weatherApiKey))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("weatherapi.com IP lookup returned HTTP " + resp.statusCode() + ": " + resp.body());
        }

        Object root = MiniJson.parse(resp.body());

        double lat = MiniJson.asDouble(MiniJson.get(root, "lat"), Double.NaN);
        double lon = MiniJson.asDouble(MiniJson.get(root, "lon"), Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            throw new RuntimeException("weatherapi.com IP lookup response did not include lat/lon: " + resp.body());
        }

        String city = MiniJson.asString(MiniJson.get(root, "city"), "");
        String region = MiniJson.asString(MiniJson.get(root, "region"), "");
        String near = city.isEmpty() ? "" : " (near " + city + (region.isEmpty() ? "" : ", " + region) + ")";

        double alt = fetchElevation(lat, lon);
        return new Reading(lat, lon, alt, "IP-based geolocation approximation (weatherapi.com IP Lookup)" + near,
                "IP geolocation resolves to city/metro-level accuracy (on the order of kilometers) at best, and can be "
                        + "off by tens to hundreds of kilometers depending on ISP routing -- NOT a device GPS fix. "
                        + "Confirm against a real GPS reading before use in a flight-critical calculation.");
    }

    private static Reading fetchViaIpApiCoFallback() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(IP_GEOLOCATION_FALLBACK_URL))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("IP geolocation fallback service returned HTTP " + resp.statusCode() + ": " + resp.body());
        }

        Object root = MiniJson.parse(resp.body());
        double lat = MiniJson.asDouble(MiniJson.get(root, "latitude"), Double.NaN);
        double lon = MiniJson.asDouble(MiniJson.get(root, "longitude"), Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            throw new RuntimeException("IP geolocation fallback response did not include latitude/longitude: " + resp.body());
        }

        String city = MiniJson.asString(MiniJson.get(root, "city"), "");
        String region = MiniJson.asString(MiniJson.get(root, "region"), "");
        String near = city.isEmpty() ? "" : " (near " + city + (region.isEmpty() ? "" : ", " + region) + ")";

        double alt = fetchElevation(lat, lon);
        return new Reading(lat, lon, alt, "IP-based geolocation approximation (ipapi.co fallback)" + near,
                "IP geolocation resolves to city/metro-level accuracy (on the order of kilometers) at best, and can be "
                        + "off by tens to hundreds of kilometers depending on ISP routing -- NOT a device GPS fix. "
                        + "Confirm against a real GPS reading before use in a flight-critical calculation.");
    }

    private static double fetchElevation(double lat, double lon) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ELEVATION_URL_BASE + lat + "," + lon))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return 0.0;

            Object root = MiniJson.parse(resp.body());
            Object results = MiniJson.get(root, "results");
            if (results instanceof List<?> list && !list.isEmpty()) {
                return MiniJson.asDouble(MiniJson.get(list.get(0), "elevation"), 0.0);
            }
            return 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}

