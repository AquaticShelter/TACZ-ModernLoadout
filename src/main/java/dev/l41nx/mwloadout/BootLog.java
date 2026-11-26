package dev.l41nx.mwloadout;

import net.minecraftforge.fml.ModList;

/**
 * Boot log: one ASCII banner + a few status lines in the console / latest.log when the mod loads, so a glance at the log says which
 * build runs on which TACZ and which version-dependent features are active. Pure logging - a failure here can never stop the game.
 */
public final class BootLog {
    private static final String[] BANNER = {
            " __  __ __        __ _",
            "|  \\/  |\\ \\      / /| |",
            "| |\\/| | \\ \\ /\\ / / | |",
            "| |  | |  \\ V  V /  | |___",
            "|_|  |_|   \\_/\\_/   |_____|"
    };

    private BootLog() {
    }

    public static void print() {
        try {
            String self = versionOf(MWLoadoutMod.MOD_ID);
            String tacz = versionOf("tacz");
            StringBuilder sb = new StringBuilder("\n");
            for (String line : BANNER) {
                sb.append(line).append('\n');
            }
            sb.append("* MW Loadout - Version: ").append(self).append(" *\n");
            sb.append("* TACZ: ").append(tacz).append(" (supported: 1.1.6 - 1.2.0) *\n");
            sb.append("* Compat: risers ").append(dev.l41nx.mwloadout.server.TaczCompat.enabled(
                    dev.l41nx.mwloadout.server.TaczCompat.Feature.RISER) ? "ON" : "off (TACZ before 1.2.0)").append(" *");
            MWLoadoutMod.LOGGER.info(sb.toString());
        } catch (Throwable t) {
            MWLoadoutMod.LOGGER.debug("[MWLoadout] boot banner skipped", t);
        }
    }

    private static String versionOf(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("not installed");
    }
}
