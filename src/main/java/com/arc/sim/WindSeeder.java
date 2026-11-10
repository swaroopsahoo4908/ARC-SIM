package com.arc.sim;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.models.wind.PinkNoiseWindModel;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Makes the multi-level wind model repeatable.
 *
 * <p>OpenRocket 24.12 builds each wind level with {@code new PinkNoiseWindModel()}, which draws a fresh random
 * seed, and the seed field is private and final. {@code SimulationOptions.setRandomSeed} never reaches those
 * level models, so two runs of the same environment see different gusts (a spread of about 3 m in apogee at 30%
 * turbulence on CSWARCMOD1E). The only way to pin them from outside is reflection on that one field, done here in
 * one place. If reflection is refused (a future OpenRocket or a locked-down JVM) the runner keeps working but
 * falls back to unseeded gusts, and says so once.
 */
final class WindSeeder {

    /** Mirrors PinkNoiseWindModel.SEED_RANDOMIZATION, which the constructor XORs into the seed. */
    private static final int SEED_RANDOMIZATION = 0x7343AA03;

    private static final Field LEVEL_MODEL_FIELD;
    private static final Field SEED_FIELD;
    private static volatile boolean warned = false;

    static {
        Field levelModel = null;
        Field seedField = null;
        try {
            levelModel = MultiLevelPinkNoiseWindModel.LevelWindModel.class.getDeclaredField("model");
            levelModel.setAccessible(true);
            seedField = PinkNoiseWindModel.class.getDeclaredField("seed");
            seedField.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            levelModel = null;
            seedField = null;
        }
        LEVEL_MODEL_FIELD = levelModel;
        SEED_FIELD = seedField;
    }

    private WindSeeder() {}

    /** SplitMix64 finaliser folded to 32 bits: nearby (seed, salt) pairs give unrelated outputs. */
    static int mix(long seed, long salt) {
        long z = seed + (salt + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) (z ^ (z >>> 32));
    }

    /** Give every level its own deterministic gust sequence derived from {@code seed}. */
    static void seedLevels(MultiLevelPinkNoiseWindModel shear, long seed) {
        if (LEVEL_MODEL_FIELD == null || SEED_FIELD == null) {
            warnOnce("reflection on OpenRocket's wind model is unavailable");
            return;
        }
        try {
            List<MultiLevelPinkNoiseWindModel.LevelWindModel> levels = shear.getLevels();
            for (int i = 0; i < levels.size(); i++) {
                PinkNoiseWindModel model = (PinkNoiseWindModel) LEVEL_MODEL_FIELD.get(levels.get(i));
                SEED_FIELD.setInt(model, mix(seed, i + 1) ^ SEED_RANDOMIZATION);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e.toString());
        }
    }

    private static void warnOnce(String why) {
        if (!warned) {
            warned = true;
            System.err.println("WARNING: could not fix the wind-noise seed (" + why + "); gusts will differ run to run.");
        }
    }
}
