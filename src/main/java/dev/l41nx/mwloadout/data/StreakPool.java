package dev.l41nx.mwloadout.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Modern Warfare style killstreak selection. {@link Rules#streakRewards} is the POOL the admin makes
 * available (each entry: kills needed + reward item). A class (LoadoutPreset) then picks at most
 * {@link #MAX_SELECTED} of them, and never two with the same kill requirement - exactly like picking
 * "Cluster Strike (5)" OR "Cruise Missile (5)", not both. Pure logic, shared by the server (which
 * grants / validates) and the client (which previews and warns before the server ever sees it).
 *
 * <p>A preset whose {@code streaks} is {@code null} has never been customised and simply uses the
 * defaults (the lowest-cost distinct entries), so existing servers keep working untouched.
 */
public final class StreakPool {

    public static final int MAX_SELECTED = 3;

    public static final int OK = 0;
    public static final int REPLACED = 1;   // same kill count was already equipped -> swapped
    public static final int FULL = 2;       // (unknown reward)
    public static final int DISPLACED = 4;  // 3 equipped, different costs -> the oldest pick was swapped out
    public static final int REMOVED = 3;    // clicked an equipped one -> unequipped

    private StreakPool() {
    }

    /** Result of a click on a pool entry: the new selection sorted by kills (what gets stored / drawn),
     *  the same ids in PICK order (oldest first - the screen keeps this to know what to swap out next),
     *  what happened, and which reward was displaced / removed (if any). */
    public record Outcome(List<String> ids, List<String> order, int status, String other) {
    }

    public static int indexOf(Rules rules, String itemId) {
        if (itemId == null) {
            return -1;
        }
        for (int i = 0; i < rules.streakRewards.size(); i++) {
            if (itemId.equals(rules.streakRewards.get(i).itemId)) {
                return i;
            }
        }
        return -1;
    }

    /** Pool indices sorted by kills, ascending (stable: equal costs keep the admin's order). */
    public static List<Integer> sortedPool(Rules rules) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rules.streakRewards.size(); i++) {
            Rules.StreakReward reward = rules.streakRewards.get(i);
            if (reward.itemId != null && !reward.itemId.isBlank()) {
                order.add(i);
            }
        }
        order.sort((a, b) -> Integer.compare(rules.streakRewards.get(a).kills, rules.streakRewards.get(b).kills));
        return order;
    }

    /** Never-customised default: the first entry of each distinct kill count, lowest first, max 3. */
    public static List<Integer> defaults(Rules rules) {
        List<Integer> out = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (int index : sortedPool(rules)) {
            if (out.size() >= MAX_SELECTED) {
                break;
            }
            if (taken.add(rules.streakRewards.get(index).kills)) {
                out.add(index);
            }
        }
        return out;
    }

    /** The pool indices this class actually has equipped: valid, distinct kill counts, max 3, ascending. */
    public static List<Integer> effective(Rules rules, LoadoutPreset preset) {
        if (preset == null || preset.streaks == null) {
            return defaults(rules);
        }
        List<Integer> out = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (String id : preset.streaks) {
            int index = indexOf(rules, id);
            if (index < 0 || out.size() >= MAX_SELECTED) {
                continue;
            }
            if (taken.add(rules.streakRewards.get(index).kills)) {
                out.add(index);
            }
        }
        out.sort((a, b) -> Integer.compare(rules.streakRewards.get(a).kills, rules.streakRewards.get(b).kills));
        return out;
    }

    /** Selection as reward ids (what a preset stores). */
    public static List<String> effectiveIds(Rules rules, LoadoutPreset preset) {
        List<String> ids = new ArrayList<>();
        for (int index : effective(rules, preset)) {
            ids.add(rules.streakRewards.get(index).itemId);
        }
        return ids;
    }

    /** Server-side clean-up for a saved preset: unknown ids, duplicate kill counts and >3 are dropped.
     *  {@code null} stays {@code null} (= defaults). */
    public static List<String> sanitize(List<String> ids, Rules rules) {
        if (ids == null) {
            return null;
        }
        LoadoutPreset probe = new LoadoutPreset();
        probe.streaks = new ArrayList<>(ids);
        return effectiveIds(rules, probe);
    }

    /** Fill every empty slot at random (distinct kill costs, max 3), exactly like empty perk columns get a random
     *  pick. Returns the selection in pick order; keeps whatever is already valid. Fewer than three come back only
     *  if the pool has fewer distinct kill costs than that. */
    public static List<String> fillRandom(Rules rules, List<String> keep, java.util.Random random) {
        List<String> ids = new ArrayList<>();
        Set<Integer> usedKills = new HashSet<>();
        if (keep != null) {
            for (String id : keep) {
                int index = indexOf(rules, id);
                if (index >= 0 && ids.size() < MAX_SELECTED && usedKills.add(rules.streakRewards.get(index).kills)) {
                    ids.add(id);
                }
            }
        }
        List<Integer> candidates = new ArrayList<>(sortedPool(rules));
        java.util.Collections.shuffle(candidates, random);
        for (int index : candidates) {
            if (ids.size() >= MAX_SELECTED) {
                break;
            }
            if (usedKills.add(rules.streakRewards.get(index).kills)) {
                ids.add(rules.streakRewards.get(index).itemId);
            }
        }
        return ids;
    }

    /** True when this class has fewer equipped killstreaks than it could (empty, never customised, or short). */
    public static boolean needsFill(Rules rules, LoadoutPreset preset) {
        if (preset == null || preset.streaks == null) {
            return !rules.streakRewards.isEmpty();
        }
        int possible = Math.min(MAX_SELECTED, distinctCosts(rules));
        return effective(rules, preset).size() < possible;
    }

    private static int distinctCosts(Rules rules) {
        Set<Integer> costs = new HashSet<>();
        for (int index : sortedPool(rules)) {
            costs.add(rules.streakRewards.get(index).kills);
        }
        return costs.size();
    }

    /** Click on a pool entry. Equipped -> unequip. Same kill cost equipped -> swap it. Free slot -> add.
     *  All slots full with different costs -> the OLDEST pick is replaced automatically (no need to unequip
     *  first). {@code currentOrder} is the selection in pick order, oldest first. */
    public static Outcome select(Rules rules, List<String> currentOrder, String itemId) {
        List<String> order = new ArrayList<>(currentOrder);
        if (order.remove(itemId)) {
            return new Outcome(sortAscending(rules, new ArrayList<>(order)), order, REMOVED, itemId);
        }
        int index = indexOf(rules, itemId);
        if (index < 0) {
            return new Outcome(sortAscending(rules, new ArrayList<>(order)), order, FULL, itemId);
        }
        int kills = rules.streakRewards.get(index).kills;
        for (int i = 0; i < order.size(); i++) {
            int other = indexOf(rules, order.get(i));
            if (other >= 0 && rules.streakRewards.get(other).kills == kills) {
                String replaced = order.remove(i); // same tier: the new pick takes its place...
                order.add(itemId);                 // ...as the freshest pick
                return new Outcome(sortAscending(rules, new ArrayList<>(order)), order, REPLACED, replaced);
            }
        }
        if (order.size() >= MAX_SELECTED) {
            String displaced = order.remove(0); // full: drop the oldest pick, no manual unequip needed
            order.add(itemId);
            return new Outcome(sortAscending(rules, new ArrayList<>(order)), order, DISPLACED, displaced);
        }
        order.add(itemId);
        return new Outcome(sortAscending(rules, new ArrayList<>(order)), order, OK, itemId);
    }

    private static List<String> sortAscending(Rules rules, List<String> ids) {
        ids.sort((a, b) -> {
            int ia = indexOf(rules, a);
            int ib = indexOf(rules, b);
            int ka = ia < 0 ? Integer.MAX_VALUE : rules.streakRewards.get(ia).kills;
            int kb = ib < 0 ? Integer.MAX_VALUE : rules.streakRewards.get(ib).kills;
            return Integer.compare(ka, kb);
        });
        return ids;
    }
}
