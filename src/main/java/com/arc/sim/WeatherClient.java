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

public class WeatherClient {
    private static final long REFRESH_INTERVAL_MS = 60L * 60L * 1000L;
    private static final String API_BASE = "https://api.weatherapi.com/v1/current.json";
    private static final String FORECAST_API_BASE = "https://api.weatherapi.com/v1/forecast.json";

    private static final int CACHE_KEY_DECIMALS = 3;

    private static class CacheEntry {
        Reading reading;
        long fetchedAtMs;
    }

    private volatile String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final java.util.Map<String, CacheEntry> cacheBySite = new java.util.HashMap<>();

    private volatile Reading lastServed;
    private volatile long lastServedFetchMs = -1;

    public WeatherClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    private static String siteKey(double lat, double lon) {
        return String.format(Locale.ROOT, "%." + CACHE_KEY_DECIMALS + "f,%." + CACHE_KEY_DECIMALS + "f", lat, lon);
    }

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

        public double estimatedWindStdDevMs() {
            return Math.max(0.0, (windGustMs - windAvgMs) / 2.5);
        }

        public String formattedFetchTime() {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(fetchedAt);
        }
    }

    public boolean hasCached() {
        return lastServed != null;
    }

    public Reading cachedReading() {
        return lastServed;
    }

    public long msUntilNextAllowedFetch() {
        if (lastServedFetchMs < 0) return 0;
        long elapsed = System.currentTimeMillis() - lastServedFetchMs;
        return Math.max(0, REFRESH_INTERVAL_MS - elapsed);
    }

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

    /**
     * Pulls a forecast for a specific future date/hour instead of right-now conditions -- lets Engine 4 plan for
     * actual launch-day timing rather than whatever the weather happens to be at the moment you click Fetch.
     * weatherapi.com's free tier only forecasts a few days out; requesting further ahead than your plan allows
     * returns an API error, surfaced as an exception here.
     */
    public Reading getForecast(double lat, double lon, java.time.LocalDate date, int hour24) throws Exception {
        String dateStr = date.toString();
        String url = FORECAST_API_BASE + "?key=" + apiKey + "&q=" + lat + "," + lon +
                "&dt=" + dateStr + "&hour=" + hour24;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Weather forecast API returned HTTP " + resp.statusCode() + ": " + resp.body());
        }
        Object root = MiniJson.parse(resp.body());
        Object location = MiniJson.get(root, "location");
        Object forecast = MiniJson.get(root, "forecast");
        Object forecastDays = forecast == null ? null : MiniJson.get(forecast, "forecastday");
        Object firstDay = MiniJson.first(forecastDays);
        Object hours = firstDay == null ? null : MiniJson.get(firstDay, "hour");
        Object hourEntry = MiniJson.first(hours);
        if (hourEntry == null) {
            throw new RuntimeException("No forecast hour data returned for " + dateStr + " " + hour24 +
                    ":00 -- this date/time may be outside your weatherapi.com plan's forecast window.");
        }

        double windKph = MiniJson.asDouble(MiniJson.get(hourEntry, "wind_kph"), 0.0);
        double gustKph = MiniJson.asDouble(MiniJson.get(hourEntry, "gust_kph"), windKph);
        double windDirDeg = MiniJson.asDouble(MiniJson.get(hourEntry, "wind_degree"), 0.0);
        double tempC = MiniJson.asDouble(MiniJson.get(hourEntry, "temp_c"), 15.0);
        double pressureMbar = MiniJson.asDouble(MiniJson.get(hourEntry, "pressure_mb"), 1013.25);
        String conditionText = MiniJson.asString(MiniJson.get(hourEntry, "condition", "text"), "unknown");
        String locationName = MiniJson.asString(MiniJson.get(location, "name"), "Unknown location");

        java.time.Instant forecastFor = java.time.LocalDateTime.of(date, java.time.LocalTime.of(hour24, 0))
                .atZone(java.time.ZoneId.systemDefault()).toInstant();

        return new Reading(locationName + " (forecast for " + dateStr + " " + String.format("%02d:00", hour24) + ")",
                windKph / 3.6, gustKph / 3.6, windDirDeg, tempC, pressureMbar, conditionText, forecastFor);
    }
}

