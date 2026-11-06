package com.metrovoc.reprise.export;

import net.neoforged.fml.ModList;

/** Queries Iris's optional public API when the export settings are opened or an export starts. */
public final class ShaderPacks {
    private ShaderPacks() {}

    public static boolean active() {
        if (!ModList.get().isLoaded("iris")) {
            return false;
        }
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return (boolean) api.getMethod("isShaderPackInUse").invoke(instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not query Iris shader pack state", e);
        }
    }
}
