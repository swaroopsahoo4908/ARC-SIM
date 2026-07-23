package com.arc.sim;

/**
 * Launch site geodetic data. Implemented as a class rather than an enum to permit construction of
 * a CUSTOM site at runtime from user-supplied latitude/longitude/altitude, while MDRA and SPAAR
 * remain fixed presets.
 *
 * NOTE: Altitude (site elevation above sea level) values for the presets below are estimates
 * derived from public sources and are not surveyed measurements. Prior to flight, confirm actual
 * field elevation via GPS or topographic reference and update the constants accordingly; site
 * elevation affects predicted apogee through its effect on air density at the pad.
 */
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

    // MDRA Central Sod Farm, 920 John Brown Road, Centreville, MD 21617.
    // Coordinates sourced from MDRA's published field GPS marker (N 39 deg 0.0266 min, W 76 deg 6.3488 min).
    public static final LaunchSite MDRA_SOD_FARM = new LaunchSite(
            "MDRA Central Sod Farm", 39.000443, -76.105813, 9.0 /* TODO verify elevation */
    );

    // SPAAR (NAR Section #503) Penn Manor field, Hambright Elementary School, 3000 Charlestown
    // Rd, Lancaster, PA 17603. Coordinates sourced from the US Census Bureau geocoder (authoritative
    // address match); elevation from Open-Elevation (SRTM-derived), not surveyed. Confirm both
    // via GPS measurement prior to operational use.
    public static final LaunchSite SPAAR_LANCASTER = new LaunchSite(
            "SPAAR Hambright Elementary / Lancaster, PA", 40.018500, -76.391953, 118.0 /* TODO verify */
    );

    /** Constructs a custom site from user-supplied coordinates. */
    public static LaunchSite custom(double latitudeDeg, double longitudeDeg, double altitudeM) {
        return new LaunchSite("Custom site", latitudeDeg, longitudeDeg, altitudeM);
    }

    public static LaunchSite custom(String label, double latitudeDeg, double longitudeDeg, double altitudeM) {
        return new LaunchSite(label, latitudeDeg, longitudeDeg, altitudeM);
    }

    /**
     * Parses a CLI/config token into a LaunchSite. Accepts "MDRA_SOD_FARM", "SPAAR_LANCASTER",
     * or "CUSTOM:lat|lon|altM" (pipe-delimited to avoid conflicting with comma-separated site
     * lists used elsewhere, e.g. sweep_grid.properties).
     */
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
