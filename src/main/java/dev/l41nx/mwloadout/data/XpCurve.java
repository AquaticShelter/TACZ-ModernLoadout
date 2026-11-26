package dev.l41nx.mwloadout.data;

/**
 * v5.2.0: shared level/XP math for BOTH player progression and per-weapon leveling (see
 * PlayerProfile.weaponXp) — MW games famously don't use a flat "same XP every level" curve; each
 * level costs a bit more than the last, so early levels fly by and later ones feel earned. This
 * stays fully backward compatible: {@code growth <= 1.0} reproduces the exact old flat formula
 * (xp / perLevel + 1) bit-for-bit, so a server that never touches the new curve setting behaves
 * identically to before this existed.
 *
 * <p>Formula: level N (N &gt; 1) costs {@code perLevel * growth^(N-2)} XP over level N-1's cost —
 * a geometric progression, the same shape MW's own level curve roughly follows (gentle early
 * ramp, noticeably steeper by the double-digit levels). {@code growth} is intentionally a small
 * multiplier (admin-tunable, sane range ~1.0–1.15) — anything much higher makes the curve
 * explode into absurd numbers within 20-30 levels.
 */
public final class XpCurve {

    private static final int MAX_LEVEL_SEARCH = 2000; // hard safety stop for the level-up loop below

    private XpCurve() {
    }

    /** Cumulative XP required to REACH `level` (level 1 = 0). */
    public static long xpForLevel(int level, int perLevel, double growth) {
        int per = Math.max(100, perLevel);
        if (level <= 1) {
            return 0;
        }
        if (growth <= 1.0001) {
            return (long) per * (level - 1); // exact old flat formula
        }
        double total = 0;
        double stepCost = per;
        for (int i = 1; i < level; i++) {
            total += stepCost;
            stepCost *= growth;
        }
        return Math.round(total);
    }

    public static int levelFor(long xp, int perLevel, double growth) {
        int per = Math.max(100, perLevel);
        if (growth <= 1.0001) {
            return (int) (xp / per) + 1; // exact old flat formula, O(1) — no need for the loop below
        }
        int level = 1;
        double stepCost = per;
        double consumed = 0;
        while (level < MAX_LEVEL_SEARCH && consumed + stepCost <= xp) {
            consumed += stepCost;
            stepCost *= growth;
            level++;
        }
        return level;
    }

    /** 0..1 progress within the CURRENT level (for an XP bar). */
    public static float progressFor(long xp, int perLevel, double growth) {
        int level = levelFor(xp, perLevel, growth);
        long floor = xpForLevel(level, perLevel, growth);
        long need = xpForLevel(level + 1, perLevel, growth) - floor;
        if (need <= 0) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, (xp - floor) / (float) need));
    }
}
