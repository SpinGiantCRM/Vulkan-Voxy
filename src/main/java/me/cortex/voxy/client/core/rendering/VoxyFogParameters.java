package me.cortex.voxy.client.core.rendering;

public record VoxyFogParameters(float environmentalStart, float environmentalEnd, float red, float green, float blue, float alpha) {
    public static final VoxyFogParameters NEUTRAL = new VoxyFogParameters(0.0f, Float.MAX_VALUE, 1.0f, 1.0f, 1.0f, 1.0f);
}
