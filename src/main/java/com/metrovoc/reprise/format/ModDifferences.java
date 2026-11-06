package com.metrovoc.reprise.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.ModList;

/** Mods that differ between a recording and this game, the usual reason recorded data cannot be read back. */
public final class ModDifferences {
    /** Loader and game mods: their changes are covered by the protocol check. */
    private static final Set<String> BASE = Set.of("minecraft", "neoforge", "reprise");

    /** Lines such as "Residual 0.6.0 → 0.7.0" or "Create 6.0 (not installed)"; empty when nothing differs. */
    public static List<String> describe(ReplayMeta meta) {
        List<String> out = new ArrayList<>();
        if (meta.mods == null) return out;
        ModList mods = ModList.get();
        for (Map.Entry<String, String> recorded : meta.mods.entrySet()) {
            if (BASE.contains(recorded.getKey())) continue;
            var installed = mods.getModContainerById(recorded.getKey());
            if (installed.isEmpty()) {
                out.add(recorded.getKey() + " " + recorded.getValue() + " (not installed)");
            } else {
                String now = installed.get().getModInfo().getVersion().toString();
                if (!now.equals(recorded.getValue())) {
                    out.add(installed.get().getModInfo().getDisplayName() + " " + recorded.getValue() + " → " + now);
                }
            }
        }
        return out;
    }

    private ModDifferences() {}
}
