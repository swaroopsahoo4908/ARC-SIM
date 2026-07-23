package com.arc.sim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Retrieves current local weather conditions from weatherapi.com for Engine 6
 * (WeatherDrivenDesign), enabling a design solve to run against measured launch-day conditions
 * rather than manually entered estimates.
 *
 * Rate limiting: one instance is created per GUI session/tab and persists for the tab's
 * lifetime. The cache/cooldown is keyed per site (rounded lat/lon), not globally:
 * getCurrent(lat,lon) issues a network request only if this is the first call for those
 * coordinates or that site's hourly cooldown has elapsed; otherwise it returns the site's cached
 * reading. This yields fetch-on-start, then at-most-hourly refresh, per site: switching the
 * launch site selector (MDRA / SPAAR / Custom) always fetches that site's current conditions
 * rather than replaying a different site's cached reading, while a repeated fetch of the same
 * site within an hour reuses the cache instead of issuing redundant API calls. No mechanism is
 * provided to bypass a site's cooldown.
 */
public class WeatherClient {
    private static final long REFRESH_INTERVAL_MS = 60L * 60L * 1000L; // 1 hour
    private static final String API_BASE = "https://api.weatherapi.com/v1/current.json";
    // Coordinates are rounded to 3 decimal places (~110 m) before use as a cache key, preventing
    // floating-point noise in a re-selected preset's lat/lon from causing a spurious cache miss
    // and an unnecessary network call.
    private static final int CACHE_KEY_DECIMALS = 3;

    private static class CacheEntry {
        Reading reading;
        long fetchedAtMs;
    }

    private volatile String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final java.util.Map<String, CacheEntry> cacheBySite = new java.util.HashMap<>();
    // Tracks the most recently fetched/served site, so cachedReading()/hasCached() (used by the
    // GUI to determine whether a reading is available to run with) reflect the currently
    // selected site rather than an arbitrary or first-fetched one.
    private volatile Reading lastServed;
    private volatile long lastServedFetchMs = -1;

    public WeatherClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Updates the API key in place (mutable rather than final) so that a key entered via
     * File > Preferences after this client was constructed -- e.g. a GUI tab built once at
     * startup -- takes effect on the next fetch without requiring an application restart, while
     * the fetch-cooldown/cache state tied to this instance is preserved.
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    private static String siteKey(double lat, double lon) {
        return String.format(Locale.ROOT, "%." + CACHE_KEY_DECIMALS + "f,%." + CACHE_KEY_DECIMALS + "f", lat, lon);
    }

    /** A single fetched weather reading, including a wind standard-deviation estimate derived from gust (not a directly measured value). */
    public static class Reading {
        public final String locationName;
        public final double windAvgMs;
        public final double windGustMs;
        public final double windDirDeg;
        public final double tempC;
        public final double pressureMbar;
        public final String conditionText;
        public final Instant fetchedAt;

        Reading(String locationName, double windAvgMs, double windGustMs, double windDirDeg,
                double tempC, double pressureMbar, String conditionText, Instant fetchedAt) {
            this.locationName = locationName;
            this.windAvgMs = windAvgMs;
            this.windGustMs = windGustMs;
            this.windDirDeg = windDirDeg;
            this.tempC = tempC;
            this.pressureMbar = pressureMbar;
            this.conditionText = conditionText;
            this.fetchedAt = fetchedAt;
        }

        /**
         * weatherapi.com reports an instantaneous gust value, not wind speed variance or standard
         * deviation, so no directly reported figure exists for the solver's windStdDevMs input.
         * This method returns an order-of-magnitude estimate only, applying the standard
         * approximation that a short-term gust sits approximately 2-3 standard deviations above
         * the mean in turbulent surface wind: (gust - avg) / 2.5. The estimate is pre-filled into
         * an editable GUI field to permit override with higher-fidelity local data (a nearby
         * anemometer log, a forecast model reporting variance, or prior field measurements at the
         * site).
         */
        public double estimatedWindStdDevMs() {
            return Math.max(0.0, (windGustMs - windAvgMs) / 2.5);
        }

        public String formattedFetchTime() {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(fetchedAt);
        }
    }

    /** Reports whether any site has been successfully fetched, i.e. whether a reading is available to run with. */
    public boolean hasCached() {
        return lastServed != null;
    }

    /** Returns the most recently fetched/served reading, for whichever site it originated from. */
    public Reading cachedReading() {
        return lastServed;
    }

    /** Returns milliseconds until the most-recently-served site's cooldown expires and it becomes eligible for re-fetch. */
    public long msUntilNextAllowedFetch() {
        if (lastServedFetchMs < 0) return 0;
        long elapsed = System.currentTimeMillis() - lastServedFetchMs;
        return Math.max(0, REFRESH_INTERVAL_MS - elapsed);
    }

    /**
     * Returns the current weather for (lat, lon), issuing a network request only if this site has
     * not previously been fetched or its hourly cooldown has elapsed; otherwise returns the
     * site's cached reading unmodified. The cache/cooldown is keyed per site (see class-level
     * documentation), so switching launch sites always retrieves that site's current conditions
     * rather than a previously cached site's reading. No override is provided to bypass a site's
     * cooldown: at most one network call per site per hour. This method executes synchronously
     * and must be invoked off the Swing EDT (background thread), consistent with all other
     * network calls.
     */
    public Reading getCurrent(double lat, double lon) throws Exception {
        String key = siteKey(lat, lon);
        CacheEntry entry = cacheBySite.get(key);
        if (entry != null) {
            long elapsed = System.currentTimeMillis() - entry.fetchedAtMs;
            if (elapsed < REFRESH_INTERVAL_MS) {
                lastServed = entry.reading;
                lastServedFetchMs = entry.fetchedAtMs;
                return entry.reading;
            }
        }
        String url = API_BASE + "?key=" + apiKey + "&q=" + lat + "," + lon;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Weather API returned HTTP " + resp.statusCode() + ": " + resp.body());
        }
        Object root = MiniJson.parse(resp.body());
        Object current = MiniJson.get(root, "current");
        Object location = MiniJson.get(root, "location");
        if (current == null) {
            throw new RuntimeException("Unexpected weather API response (no 'current' field): " + resp.body());
        }

        double windKph = MiniJson.asDouble(MiniJson.get(current, "wind_kph"), 0.0);
        double gustKph = MiniJson.asDouble(MiniJson.get(current, "gust_kph"), windKph);
        double windDirDeg = MiniJson.asDouble(MiniJson.get(current, "wind_degree"), 0.0);
        double tempC = MiniJson.asDouble(MiniJson.get(current, "temp_c"), 15.0);
        double pressureMbar = MiniJson.asDouble(MiniJson.get(current, "pressure_mb"), 1013.25);
        String conditionText = MiniJson.asString(MiniJson.get(current, "condition", "text"), "unknown");
        String locationName = MiniJson.asString(MiniJson.get(location, "name"), "Unknown location");

        Reading r = new Reading(locationName, windKph / 3.6, gustKph / 3.6, windDirDeg, tempC, pressureMbar,
                conditionText, Instant.now());
        long now = System.currentTimeMillis();
        CacheEntry newEntry = new CacheEntry();
        newEntry.reading = r;
        newEntry.fetchedAtMs = now;
        cacheBySite.put(key, newEntry);
        lastServed = r;
        lastServedFetchMs = now;
        return r;
    }
}
