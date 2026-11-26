package dev.l41nx.mwloadout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where an exception that is deliberately NOT propagated goes. A swallowed exception is sometimes right (a hand-edited json entry, an optional TACZ call
 * that does not exist in this version, a render helper that must never crash the game), but silently dropping it hides real bugs. Each call site logs ONCE
 * with its stack trace (so a log is never flooded, even from a per-frame path) and then only counts; a repeat count is logged at 100 and at 10 000.
 */
public final class MWLog {
    private static final Map<String, AtomicInteger> SEEN = new ConcurrentHashMap<>();

    private MWLog() {
    }

    /** Server-side / data paths: the first occurrence at the site is a WARN, an admin should see it once. */
    public static void swallowed(String site, Throwable t) {
        log(site, t, true);
    }

    /** Client-side probes and render helpers: the first occurrence is DEBUG (enable debug logging for the mod to see it). */
    public static void quiet(String site, Throwable t) {
        log(site, t, false);
    }

    private static void log(String site, Throwable t, boolean warn) {
        int n = SEEN.computeIfAbsent(site, k -> new AtomicInteger()).incrementAndGet();
        if (n == 1) {
            if (warn) {
                MWLoadoutMod.LOGGER.warn("[MWLoadout] Ignored an exception at {} (first time; repeats are only counted)", site, t);
            } else {
                MWLoadoutMod.LOGGER.debug("[MWLoadout] Ignored an exception at {} (first time; repeats are only counted)", site, t);
            }
        } else if (n == 100 || n == 10_000) {
            MWLoadoutMod.LOGGER.debug("[MWLoadout] Exception at {} ignored {} times so far", site, n);
        }
    }
}
