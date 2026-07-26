package com.arc.sim;

public class LaunchSite {

    public final String label;
    public final double latitudeDeg;
    public final double longitudeDeg;
    public final double altitudeM;

    private LaunchSite(String label, double latitudeDeg, double longitudeDeg, double altitudeM) {
        this.label = label;
        this.latitudeDeg = latitudeDeg;
        this.longitudeDeg = longitudeDeg;
        this.altitudeM = altitudeM;
    }

    public static final LaunchSite MDRA_SOD_FARM = new LaunchSite(
            "MDRA Central Sod Farm", 39.000443, -76.105813, 9.0
    );

    public static final LaunchSite SPAAR_LANCASTER = new LaunchSite(
            "SPAAR Hambright Elementary / Lancaster, PA", 40.018500, -76.391953, 118.0
    );

    public static LaunchSite custom(double latitudeDeg, double longitudeDeg, double altitudeM) {
        return new LaunchSite("Custom site", latitudeDeg, longitudeDeg, altitudeM);
    }

    public static LaunchSite custom(String label, double latitudeDeg, double longitudeDeg, double altitudeM) {
        return new LaunchSite(label, latitudeDeg, longitudeDeg, altitudeM);
    }

    public static LaunchSite parse(String spec) {
        String s = spec.trim();
        if (s.equalsIgnoreCase("MDRA_SOD_FARM")) return MDRA_SOD_FARM;
        if (s.equalsIgnoreCase("SPAAR_LANCASTER")) return SPAAR_LANCASTER;
        if (s.toUpperCase().startsWith("CUSTOM:")) {
            String[] parts = s.substring("CUSTOM:".length()).split("\\|");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Custom site spec must be CUSTOM:lat|lon|altM, got: " + spec);
            }
            return custom(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
        }
        throw new IllegalArgumentException("Unknown site: " + spec +
                " (expected MDRA_SOD_FARM, SPAAR_LANCASTER, or CUSTOM:lat|lon|altM)");
    }

    @Override
    public String toString() {
        return label;
    }
}

