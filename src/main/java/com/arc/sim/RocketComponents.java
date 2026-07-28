package com.arc.sim;

import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RocketComponents {

    public static BodyTube findLowestBodyTube(Rocket rocket) {
        List<RocketComponent> topLevel = rocket.getChildren();
        if (topLevel.isEmpty()) {
            throw new IllegalStateException("Rocket has no top-level components -- is this a valid .ork file?");
        }
        RocketComponent lastStage = topLevel.get(topLevel.size() - 1);

        BodyTube lowest = null;
        for (RocketComponent c : lastStage.getChildren()) {
            if (c instanceof BodyTube) lowest = (BodyTube) c;
        }
        if (lowest == null) {
            throw new IllegalStateException("No BodyTube found in stage '" + lastStage.getName() + "'.");
        }
        return lowest;
    }

    public static List<MassComponent> findBallastComponents(Rocket rocket) {
        BodyTube lowest = findLowestBodyTube(rocket);
        List<MassComponent> ballast = new ArrayList<>();
        for (RocketComponent c : lowest.getChildren()) {
            if (c instanceof MassComponent) ballast.add((MassComponent) c);
        }
        if (ballast.isEmpty()) {
            throw new IllegalStateException("No MassComponent found inside the lowest body tube ('" +
                    lowest.getName() + "'). Add one there in the OpenRocket GUI to act as ballast " +
                    "(the solver will drive its mass; starting value doesn't matter).");
        }
        return ballast;
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

        public void setHoleRadiusM(double holeRadiusM) {
            double holeDiam = 2.0 * Math.max(0.0, holeRadiusM);
            double underRoot = (baseDiameterM * baseDiameterM) - (holeDiam * holeDiam);
            double effectiveDiameterM = underRoot > 0 ? Math.sqrt(underRoot) : 0.0;
            chute.setDiameter(effectiveDiameterM);
        }

        public void clearHole() {
            chute.setDiameter(baseDiameterM);
        }
    }
}

