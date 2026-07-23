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

/**
 * Resolves the operator's current position for use as a launch-site coordinate, in place of
 * manual lat/lon/altitude entry.
 *
 * Three acquisition paths are attempted, in order of decreasing accuracy:
 *
 * 1. On-device positioning via CoreLocationCLI (macOS-only, third-party, not bundled with this
 *    project -- install separately via {@code brew install corelocationcli}). Where present, this
 *    reflects the operating system's own location fix. Desktop/laptop Macs have no dedicated GPS
 *    receiver, so this fix comes from CoreLocation's Wi-Fi Positioning System (matching visible
 *    access points against Apple's crowdsourced AP-location database) rather than true satellite
 *    GPS -- accuracy is generally good in densely mapped urban areas but can be off by a mile or
 *    more in sparsely mapped rural areas, even on a successful (non-error) fix. The tool prompts
 *    the operator for Location Services permission on first use, consistent with normal macOS
 *    application behavior. This remains the most accurate of the three paths and the only one
 *    tied to the physical machine rather than network routing; the two IP-based paths below
 *    resolve only the network egress point, which can be materially displaced from the operator's
 *    actual location depending on ISP/carrier routing (see note below).
 * 2. IP-address-based geolocation via weatherapi.com's IP Lookup endpoint, using the same API key
 *    and provider already used for Engine 6's weather pull (see WeatherClient.java), so both
 *    features are backed by one account/geolocation database rather than two independent, and
 *    potentially disagreeing, services. Used whenever CoreLocationCLI is unavailable.
 * 3. IP-address-based geolocation via ipapi.co, used only if the weatherapi.com IP lookup itself
 *    fails outright (bad key, rate limit, network error) -- a last-resort second opinion, not a
 *    routinely preferred source.
 *
 * IMPORTANT ACCURACY NOTE: both IP-based paths resolve a location from the network's public IP
 * address via a geolocation database, NOT the operator's physical position. Many ISPs -- and
 * especially cellular, satellite, or backbone-routed connections -- register an IP block's
 * location as a distant routing hub or regional NOC rather than the subscriber's actual location,
 * so an IP-based result can legitimately be off by tens to hundreds of kilometers regardless of
 * which provider is queried. This is a structural limitation of IP geolocation, not a defect in
 * either provider's database. CoreLocationCLI is the only path immune to this specific failure
 * mode, since it queries the OS's own location fix (Wi-Fi Positioning System on typical Mac
 * hardware; see note above on its own accuracy limits) rather than inferring a position from
 * network routing.
 *
 * Altitude is not returned by any source at usable precision (CoreLocationCLI's altitude figure is
 * coarse; neither IP-geolocation path returns one at all), so every path resolves altitude via the
 * same Open-Elevation lookup (SRTM-derived) already used for launch-site elevation elsewhere in
 * this project (see LaunchSite.java).
 *
 * Every Reading carries an explicit source label and accuracy caveat so the GUI can present the
 * operator with an honest statement of provenance rather than an unqualified coordinate -- this
 * mirrors the disclaimers already attached to the two fixed LaunchSite presets.
 */
public class DeviceLocation {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final String WEATHERAPI_IP_LOOKUP_URL_BASE = "https://api.weatherapi.com/v1/ip.json?q=auto:ip&key=";
    private static final String IP_GEOLOCATION_FALLBACK_URL = "https://ipapi.co/json/";
    private static final String ELEVATION_URL_BASE = "https://api.open-elevation.com/api/v1/lookup?locations=";
    private static final int CORE_LOCATION_CLI_TIMEOUT_S = 12;

    /** A single resolved position, tagged with its acquisition method and an operator-facing accuracy statement. */
    public static class Reading {
        public final double latitudeDeg;
        public final double longitudeDeg;
        public final double altitudeM;
        public final String source;
        public final String accuracyNote;
        /** CoreLocationCLI's own reported horizontal accuracy radius, in meters; NaN if not reported (e.g. IP-based paths). */
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

    /**
     * Resolves the current position, preferring an on-device fix, then the weatherapi.com
     * IP-lookup (same provider/account as Engine 6's weather pull), then ipapi.co as a final
     * fallback. Executes synchronously (blocking network I/O) and must be invoked off the Swing
     * EDT. Throws only if every acquisition path fails outright (e.g. no network connectivity); a
     * missing or non-functional CoreLocationCLI is not an error condition and silently defers to
     * the IP-based paths.
     *
     * @param weatherApiKey the same weatherapi.com API key used for Engine 6's weather pull
     */
    public static Reading fetch(String weatherApiKey) throws Exception {
        Reading onDevice = tryCoreLocationCli();
        if (onDevice != null) return onDevice;
        try {
            return fetchViaWeatherApiIpLookup(weatherApiKey);
        } catch (Exception primaryFailure) {
            return fetchViaIpApiCoFallback();
        }
    }

    /**
     * Attempts an on-device fix via CoreLocationCLI. Returns null (not an exception) for any
     * condition that should transparently defer to the IP-geolocation fallback: the binary is not
     * installed, the process fails to start, it does not exit within the timeout window, it exits
     * non-zero (including a permission denial), or its output does not parse as the expected JSON
     * shape. Every fallback trigger is logged to standard error so a misconfiguration (e.g. an
     * unrecognized CLI flag, or a change to the tool's output format in a future release) is
     * diagnosable from the run log rather than silently indistinguishable from "not installed".
     *
     * CoreLocationCLI's documented invocation is {@code CoreLocationCLI [--watch] --json}; with no
     * {@code --watch}, the tool takes a single reading and exits on its own, so no separate
     * "run once" flag exists (an earlier revision of this method passed a nonexistent {@code
     * -once} flag, which the CLI rejected outright). Its JSON output additionally reports
     * latitude/longitude/altitude as quoted strings, not JSON numbers (e.g. {@code
     * "latitude":"40.141196"}), so numeric fields are parsed with {@link #asFlexibleDouble}
     * rather than {@link MiniJson#asDouble}, which only recognizes unquoted JSON numbers.
     */
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

            // Desktop/laptop Macs have no dedicated GPS receiver: CoreLocation resolves position via
            // Wi-Fi Positioning System (matching visible access points against Apple's crowdsourced
            // AP-location database), falling back to IP geolocation only if no AP match is found.
            // WPS accuracy scales with how densely an area's access points have been surveyed by
            // Apple/passing devices -- dense urban areas are often accurate to tens of meters, but
            // low-density rural areas can legitimately be off by a mile or more even with a
            // successful, non-error fix. h_accuracy below is CoreLocation's own confidence radius
            // for this specific fix, which can itself understate the true error (see above) but is
            // the best signal this tool has to offer.
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

    /**
     * Coerces a MiniJson-parsed value to a double whether it was encoded as a JSON number or (as
     * CoreLocationCLI's {@code --json} output does for latitude/longitude/altitude) a quoted
     * string. Returns NaN for null, non-numeric strings, or any other type.
     */
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

    /**
     * IP-address-based geolocation via weatherapi.com's IP Lookup endpoint, using the same account
     * already relied on for Engine 6's weather pull. Primary IP-based path; used whenever
     * CoreLocationCLI is unavailable.
     */
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
        // weatherapi.com's IP Lookup endpoint nests its single result under "ip_addr" or returns it
        // as the root object depending on API version; the fields of interest ("lat"/"lon", not
        // "latitude"/"longitude") are read directly off the root here, matching the documented
        // IP Lookup response shape.
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

    /**
     * IP-address-based geolocation via ipapi.co, used only if the weatherapi.com IP lookup itself
     * fails outright (bad key, rate limit, network error) -- a last-resort second opinion, not a
     * routinely preferred source.
     */
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

    /**
     * Resolves ground elevation for (lat, lon) via Open-Elevation (SRTM-derived), the same source
     * already used for LaunchSite's unsurveyed elevation values. Returns 0.0 on any failure rather
     * than propagating an exception -- a missing elevation should not block populating a usable
     * lat/lon fix.
     */
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
