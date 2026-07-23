package com.arc.sim;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Locates ballast and recovery components by structural position rather than by name, since
 * MassComponent instances in the reference design file (CSWARCMOD1D.ork, and comparable
 * iterations) are labeled generically ("Mass Component") and provide no unique name to match
 * against.
 *
 * Component identification convention:
 *   - Ballast: MassComponent(s) located directly inside the lowest (bottommost/aft-most)
 *     BodyTube of the rocket, i.e. the tube also containing the fin set and motor mount in the
 *     reference design. When multiple MassComponents occupy that tube, their masses are scaled
 *     together proportionally, preserving their relative split, when the solver adjusts total
 *     ballast mass.
 *   - Recovery: the first Parachute component found anywhere in the component tree.
 *
 * If ballast is relocated in a future design revision (e.g. a dedicated ballast tube),
 * findLowestBodyTube() below must be updated accordingly, for example to match a specific body
 * tube name or id rather than "last body tube in the last top-level stage".
 *
 * This class deliberately avoids importing or referencing OpenRocket's stage class by name, as
 * it has been renamed across core versions (Stage vs. AxialStage). Instead, the last top-level
 * child of Rocket is treated as "the stage" (valid for single-stage rockets regardless of the
 * underlying class name), and its children are walked generically.
 */
public class RocketComponents {

    public static BodyTube findLowestBodyTube(Rocket rocket) {
        List<RocketComponent> topLevel = rocket.getChildren();
        if (topLevel.isEmpty()) {
            throw new IllegalStateException("Rocket has no top-level components -- is this a valid .ork file?");
        }
        RocketComponent lastStage = topLevel.get(topLevel.size() - 1); // Last stage: sustainer, or the only stage.

        BodyTube lowest = null;
        for (RocketComponent c : lastStage.getChildren()) {
            if (c instanceof BodyTube) lowest = (BodyTube) c; // Last match wins; corresponds to the bottommost tube.
        }
        if (lowest == null) {
            throw new IllegalStateException("No BodyTube found in stage '" + lastStage.getName() + "'.");
        }
        return lowest;
    }

    public static List<MassComponent> findBallastComponents(Rocket rocket) {
        BodyTube lowest = findLowestBodyTube(rocket);
        List<MassComponent> result = new ArrayList<>();
        for (RocketComponent c : lowest.getChildren()) {
            if (c instanceof MassComponent) result.add((MassComponent) c);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No MassComponent found inside the lowest body tube ('" +
                    lowest.getName() + "'). Add one there in the OpenRocket GUI to act as ballast " +
                    "(the solver will drive its mass; starting value doesn't matter).");
        }
        return result;
    }

    public static Parachute findMainParachute(Rocket rocket) {
        return findFirst(rocket, Parachute.class)
                .orElseThrow(() -> new IllegalStateException("No Parachute component found anywhere in the rocket."));
    }

    public static info.openrocket.core.rocketcomponent.TrapezoidFinSet findFinSet(Rocket rocket) {
        return findFirst(rocket, info.openrocket.core.rocketcomponent.TrapezoidFinSet.class)
                .orElseThrow(() -> new IllegalStateException("No TrapezoidFinSet found anywhere in the rocket."));
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> findFirst(RocketComponent node, Class<T> type) {
        if (type.isInstance(node)) return Optional.of((T) node);
        for (RocketComponent child : node.getChildren()) {
            Optional<T> r = findFirst(child, type);
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    /**
     * Controls a group of MassComponents as a single total-ballast-mass parameter, preserving
     * their original relative mass split (or distributing evenly if all components started at
     * approximately zero mass).
     */
    public static class BallastControl {
        private final List<MassComponent> components;
        private final double[] baseMasses;
        private final double baseSum;

        public BallastControl(List<MassComponent> components) {
            this.components = components;
            this.baseMasses = new double[components.size()];
            double sum = 0;
            for (int i = 0; i < components.size(); i++) {
                baseMasses[i] = components.get(i).getComponentMass();
                sum += baseMasses[i];
            }
            this.baseSum = sum;
        }

        public void setTotalKg(double totalKg) {
            if (baseSum <= 1e-9) {
                double each = totalKg / components.size();
                for (MassComponent m : components) m.setComponentMass(each);
            } else {
                for (int i = 0; i < components.size(); i++) {
                    components.get(i).setComponentMass(totalKg * (baseMasses[i] / baseSum));
                }
            }
        }

        public double getCurrentTotalKg() {
            double sum = 0;
            for (MassComponent m : components) sum += m.getComponentMass();
            return sum;
        }

        public int count() {
            return components.size();
        }
    }

    /**
     * Models a circular center spill hole cut into the main parachute canopy as an
     * effective-diameter reduction: a hole of radius r removes pi*r^2 of canopy area, so the
     * equivalent full circular canopy producing the same open area has effectiveDiameter =
     * sqrt(fullDiameter^2 - (2r)^2). This is a standard approximation for spill-hole drag
     * reduction, chosen for robustness against Parachute-Cd API differences across OpenRocket
     * core versions.
     *
     * The chute's original diameter, as loaded from the source .ork file, is captured once at
     * construction and used as the reference full-canopy size for every subsequent hole-radius
     * setting, preventing compounding error across repeated setHoleRadiusM() calls during a
     * bisection search.
     */
    public static class ParachuteHoleControl {
        private final Parachute chute;
        private final double baseDiameterM;

        public ParachuteHoleControl(Parachute chute) {
            this.chute = chute;
            this.baseDiameterM = chute.getDiameter();
        }

        public double getBaseDiameterM() {
            return baseDiameterM;
        }

        /** Sets the spill-hole radius (meters); clamps to keep the effective diameter non-negative. */
        public void setHoleRadiusM(double holeRadiusM) {
            double holeDiam = 2.0 * Math.max(0.0, holeRadiusM);
            double underRoot = (baseDiameterM * baseDiameterM) - (holeDiam * holeDiam);
            double effectiveDiameterM = underRoot > 0 ? Math.sqrt(underRoot) : 0.0;
            chute.setDiameter(effectiveDiameterM);
        }

        /** Restores the parachute to its original (no-hole) diameter. */
        public void clearHole() {
            chute.setDiameter(baseDiameterM);
        }
    }
}
