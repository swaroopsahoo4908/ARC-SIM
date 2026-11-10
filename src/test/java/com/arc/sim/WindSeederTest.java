package com.arc.sim;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.models.wind.WindModel;
import info.openrocket.core.startup.OpenRocketCore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the reflection hack against an OpenRocket upgrade that moves or renames the private seed field. */
class WindSeederTest {

    @BeforeAll
    static void initOpenRocket() {
        OpenRocketCore.initialize();
    }

    private static MultiLevelPinkNoiseWindModel build() {
        MultiLevelPinkNoiseWindModel m = new MultiLevelPinkNoiseWindModel();
        m.clearLevels();
        m.setAltitudeReference(WindModel.AltitudeReference.AGL);
        for (double h : new double[]{0, 10, 50, 200, 1000}) {
            double speed = h <= 0 ? 0 : 6.0 * Math.pow(h / 10.0, 0.14);
            m.addWindLevel(h, speed, 1.0, speed * 0.4);   // altitude, speed, direction, stddev
        }
        return m;
    }

    private static double[] sample(MultiLevelPinkNoiseWindModel m) {
        double[] out = new double[40];
        int k = 0;
        for (int step = 0; step < 10; step++) {
            for (double alt : new double[]{15, 80, 300, 900}) {
                out[k++] = m.getWindVelocity(step * 0.5, alt).x;
            }
        }
        return out;
    }

    @Test
    void sameSeedGivesTheSameGustsAndDifferentSeedsDoNot() {
        MultiLevelPinkNoiseWindModel a = build(), b = build(), c = build();
        WindSeeder.seedLevels(a, 99L);
        WindSeeder.seedLevels(b, 99L);
        WindSeeder.seedLevels(c, 100L);
        assertArrayEquals(sample(a), sample(b), 0.0);
        assertFalse(java.util.Arrays.equals(sample(a), sample(c)), "different seeds produced identical gusts");
    }

    @Test
    void unseededModelsDifferBetweenBuilds() {
        // This is the OpenRocket behaviour the seeder exists to fix; if it ever stops being true the seeder may be unnecessary.
        assertFalse(java.util.Arrays.equals(sample(build()), sample(build())));
    }

    @Test
    void levelsGetIndependentSequences() {
        MultiLevelPinkNoiseWindModel m = new MultiLevelPinkNoiseWindModel();
        m.clearLevels();
        m.addWindLevel(0, 5.0, 0.0, 2.0);
        m.addWindLevel(100, 5.0, 0.0, 2.0);
        WindSeeder.seedLevels(m, 5L);
        double g0 = m.getWindVelocity(1.0, 0.0).y - 5.0;
        double g1 = m.getWindVelocity(1.0, 100.0).y - 5.0;
        assertNotEquals(g0, g1, 1e-12);
    }

    @Test
    void mixIsDeterministicAndSpreadsNearbyInputs() {
        assertEquals(WindSeeder.mix(1, 2), WindSeeder.mix(1, 2));
        assertNotEquals(WindSeeder.mix(1, 2), WindSeeder.mix(1, 3));
        assertNotEquals(WindSeeder.mix(1, 2), WindSeeder.mix(2, 2));
    }
}
