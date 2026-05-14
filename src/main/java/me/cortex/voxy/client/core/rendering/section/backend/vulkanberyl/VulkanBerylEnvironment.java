package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.common.Logger;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared parser for Vulkan/Beryl environment and JVM property feature flags.
 */
final class VulkanBerylEnvironment {
    private static final String PREFIX = "[Voxy][VulkanBeryl] ";
    private static final Set<String> LOGGED_FLAGS = ConcurrentHashMap.newKeySet();

    private VulkanBerylEnvironment() {
    }

    static boolean flag(String name) {
        return flag(name, false);
    }

    static boolean flag(String name, boolean defaultValue) {
        FlagValue value = flagValue(name, defaultValue);
        if (value.hasRawValue() && LOGGED_FLAGS.add(name)) {
            Logger.info(PREFIX + "boolean flag " + name
                    + " parsed=" + value.parsed()
                    + ", source=" + value.source()
                    + ", raw='" + value.rawValue() + "'");
        }
        return value.parsed();
    }

    static FlagValue flagValue(String name, boolean defaultValue) {
        String envValue = System.getenv(name);
        if (hasText(envValue)) {
            return parse(name, "environment", envValue);
        }
        String propertyValue = System.getProperty(name);
        if (hasText(propertyValue)) {
            return parse(name, "system-property", propertyValue);
        }
        return new FlagValue(name, defaultValue, "default", null);
    }

    private static FlagValue parse(String name, String source, String rawValue) {
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        boolean parsed = switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> false;
        };
        return new FlagValue(name, parsed, source, rawValue);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record FlagValue(String name, boolean parsed, String source, String rawValue) {
        boolean hasRawValue() {
            return rawValue != null;
        }

        String diagnostic() {
            if (!hasRawValue()) {
                return name + "=" + parsed + " (default)";
            }
            return name + "=" + parsed + " (source=" + source + ", raw='" + rawValue + "')";
        }
    }
}
