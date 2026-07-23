package com.arc.sim;

import info.openrocket.core.rocketcomponent.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Introspects a rocket component tree generically so Engine 3 operates on any .ork file
 * structure (single-stage, multi-stage, or otherwise), without assuming a specific layout.
 *
 * Rather than inferring a single canonical MassComponent/Parachute/FinSet, an approach that
 * fails when a rocket has both drogue and main chutes, fins on multiple stages, or ballast in an
 * unexpected location, this class enumerates every candidate with a human-readable label and a
 * best-guess default, deferring final selection to the caller (GUI or CLI).
 */
public class RocketInspector {

    public static class Item<T> {
        public final T component;
        public final String label;
        public Item(T component, String label) {
            this.component = component;
            this.label = label;
        }
        @Override public String toString() { return label; }
    }

    public static List<Item<MassComponent>> listMassComponents(Rocket rocket) {
        List<Item<MassComponent>> result = new ArrayList<>();
        collect(rocket, MassComponent.class, "", result);
        return result;
    }

    public static List<Item<Parachute>> listParachutes(Rocket rocket) {
        List<Item<Parachute>> result = new ArrayList<>();
        collect(rocket, Parachute.class, "", result);
        return result;
    }

    public static List<Item<TrapezoidFinSet>> listTrapezoidFinSets(Rocket rocket) {
        List<Item<TrapezoidFinSet>> result = new ArrayList<>();
        collect(rocket, TrapezoidFinSet.class, "", result);
        return result;
    }

    /** Best-guess default: the mass component in the lowest (aft-most) body tube of the last stage. */
    public static MassComponent suggestBallastDefault(Rocket rocket) {
        try {
            List<RocketComponent> topLevel = rocket.getChildren();
            if (topLevel.isEmpty()) return null;
            RocketComponent lastStage = topLevel.get(topLevel.size() - 1);
            BodyTube lowestTube = null;
            for (RocketComponent c : lastStage.getChildren()) {
                if (c instanceof BodyTube) lowestTube = (BodyTube) c;
            }
            if (lowestTube == null) return null;
            MassComponent found = null;
            for (RocketComponent c : lowestTube.getChildren()) {
                if (c instanceof MassComponent) found = (MassComponent) c;
            }
            return found;
        } catch (Exception e) {
            return null; // Caller must handle a null result (no default available; requires manual selection).
        }
    }

    /** Best-guess default: the largest-diameter parachute, since main canopies are typically larger than drogues. */
    public static Parachute suggestMainParachuteDefault(List<Item<Parachute>> parachutes) {
        Parachute best = null;
        double bestDia = -1;
        for (Item<Parachute> p : parachutes) {
            try {
                double dia = p.component.getDiameter();
                if (dia > bestDia) {
                    bestDia = dia;
                    best = p.component;
                }
            } catch (Exception ignored) {
                // Skip components whose diameter cannot be read.
            }
        }
        return best;
    }

    /** Best-guess default: the last (aft-most) trapezoidal fin set found, typically the booster or sustainer fins. */
    public static TrapezoidFinSet suggestFinSetDefault(List<Item<TrapezoidFinSet>> finSets) {
        return finSets.isEmpty() ? null : finSets.get(finSets.size() - 1).component;
    }

    @SuppressWarnings("unchecked")
    private static <T> void collect(RocketComponent node, Class<T> type, String pathPrefix, List<Item<T>> out) {
        String nodeName = safeName(node);
        String path = pathPrefix.isEmpty() ? nodeName : pathPrefix + " > " + nodeName;
        if (type.isInstance(node)) {
            String extra = describeExtra(node);
            out.add(new Item<>((T) node, path + (extra.isEmpty() ? "" : " (" + extra + ")")));
        }
        for (RocketComponent child : node.getChildren()) {
            collect(child, type, path, out);
        }
    }

    private static String safeName(RocketComponent c) {
        try {
            String n = c.getName();
            return (n == null || n.isBlank()) ? c.getClass().getSimpleName() : n;
        } catch (Exception e) {
            return c.getClass().getSimpleName();
        }
    }

    private static String describeExtra(RocketComponent c) {
        try {
            if (c instanceof MassComponent) {
                return String.format("%.1f g", ((MassComponent) c).getComponentMass() * 1000);
            }
            if (c instanceof Parachute) {
                return String.format("%.3f m dia", ((Parachute) c).getDiameter());
            }
            if (c instanceof TrapezoidFinSet) {
                TrapezoidFinSet f = (TrapezoidFinSet) c;
                return String.format("h=%.3f m", f.getHeight());
            }
        } catch (Exception ignored) {
            // Best-effort only; if an accessor fails, omit the supplementary detail.
        }
        return "";
    }
}
