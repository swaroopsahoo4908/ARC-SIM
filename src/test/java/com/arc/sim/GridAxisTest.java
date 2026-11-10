package com.arc.sim;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class GridAxisTest {

    @Test
    void evenlyDividingRangeIncludesBothEnds() {
        GridAxis a = new GridAxis(0, 22, 0.5);
        assertEquals(45, a.count());
        assertEquals(0.0, a.value(0));
        assertEquals(22.0, a.value(44), 1e-12);
    }

    @Test
    void floatingPointStepStillReachesTheLastPoint() {
        assertEquals(11, new GridAxis(0, 1, 0.1).count());
        assertEquals(31, new GridAxis(0, 3, 0.1).count());
    }

    @Test
    void stepThatDoesNotDivideTheRangeNeverExceedsMax() {
        // Used to give 3 points (0, 5, 10) with the last one outside the 0..8 range.
        GridAxis a = new GridAxis(0, 8, 5);
        assertEquals(2, a.count());
        for (int i = 0; i < a.count(); i++) {
            assertTrue(a.value(i) <= 8.0, "value " + a.value(i) + " is past max");
        }
    }

    @Test
    void singlePointAxisAcceptsAnyStep() {
        assertEquals(1, new GridAxis(7, 7, 0).count());
        assertEquals(1, new GridAxis(0, 0, 1).count());
    }

    @Test
    void zeroOrNegativeStepOnARealRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GridAxis(0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new GridAxis(0, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new GridAxis(10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new GridAxis(Double.NaN, 1, 1));
    }

    @Test
    void absurdPointCountIsRejectedInsteadOfOverflowing() {
        assertThrows(IllegalArgumentException.class, () -> new GridAxis(0, 1e15, 1e-3).count());
    }

    @Test
    void fromRangeAndCountMatchesRequestedCount() {
        GridAxis a = GridAxis.fromRangeAndCount(0, 22, 45);
        assertEquals(45, a.count());
        assertEquals(1, GridAxis.fromRangeAndCount(0, 6, 1).count());
        assertEquals(1, GridAxis.fromRangeAndCount(5, 5, 9).count());
        assertEquals(1, GridAxis.fromRangeAndCount(9, 3, 4).count());
    }

    @Test
    void shippedDefaultGridIsTheDocumentedSize() throws Exception {
        File props = new File("sweep_grid.properties");
        org.junit.jupiter.api.Assumptions.assumeTrue(props.exists());
        GridAxis.SweepConfig cfg = GridAxis.load(props);
        assertEquals(145_530L, cfg.totalCombos());
        assertEquals(243.84, cfg.targetApogeeM, 1e-12);
        assertEquals(0.25, cfg.apogeeToleranceM, 1e-12);
    }
}
