package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.rendering.VoxyFogParameters;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

public final class SodiumFogBridge {
    private static final String SODIUM_FOG_PARAMETERS_CLASS = "net.caffeinemc.mods.sodium.client.util.FogParameters";

    private SodiumFogBridge() {}

    public static VoxyFogParameters resolveFogParameters() {
        if (!FabricLoader.getInstance().isModLoaded("sodium")) {
            return null;
        }
        try {
            var gameRenderer = Minecraft.getInstance().gameRenderer;
            Method getFogMethod = gameRenderer.getClass().getMethod("sodium$getFogParameters");
            Object fogParameters = getFogMethod.invoke(gameRenderer);
            if (fogParameters == null) {
                return null;
            }
            return fromSodiumFogParameters(fogParameters);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.warn("[voxy] Failed to reflect Sodium fog parameters", e);
            return null;
        }
    }

    public static VoxyFogParameters fromSodiumFogParameters(Object fogParameters) {
        if (fogParameters == null) {
            return VoxyFogParameters.NEUTRAL;
        }
        try {
            if (!SODIUM_FOG_PARAMETERS_CLASS.equals(fogParameters.getClass().getName())) {
                return VoxyFogParameters.NEUTRAL;
            }
            Class<?> fogClass = fogParameters.getClass();
            return new VoxyFogParameters(
                    ((Number) fogClass.getMethod("environmentalStart").invoke(fogParameters)).floatValue(),
                    ((Number) fogClass.getMethod("environmentalEnd").invoke(fogParameters)).floatValue(),
                    ((Number) fogClass.getMethod("red").invoke(fogParameters)).floatValue(),
                    ((Number) fogClass.getMethod("green").invoke(fogParameters)).floatValue(),
                    ((Number) fogClass.getMethod("blue").invoke(fogParameters)).floatValue(),
                    ((Number) fogClass.getMethod("alpha").invoke(fogParameters)).floatValue()
            );
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.warn("[voxy] Failed to convert Sodium fog parameters", e);
            return VoxyFogParameters.NEUTRAL;
        }
    }
}
