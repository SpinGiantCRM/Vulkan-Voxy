package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import me.cortex.voxy.common.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared logging gate for the Vulkan/Beryl backend.
 *
 * <p>Default runtime logging is intentionally quiet: setup diagnostics are emitted once per JVM,
 * while frame/bind traces are disabled unless VOXY_VULKAN_BERYL_TRACE_LOGS=true.</p>
 */
final class VulkanBerylDebugLog {
    static final boolean VERBOSE_LOGS = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_VERBOSE_LOGS", "false"));
    static final boolean TRACE_LOGS = Boolean.parseBoolean(System.getenv().getOrDefault("VOXY_VULKAN_BERYL_TRACE_LOGS", "false"));

    private static final String PREFIX = "[Voxy][VulkanBeryl] ";
    private static final int DEFAULT_WARN_INTERVAL_FRAMES = 300;
    private static final long DEFAULT_WARN_INTERVAL_NANOS = 10_000_000_000L;
    private static final int DEFAULT_STATE_INTERVAL_FRAMES = 18_000;
    private static final long DEFAULT_STATE_INTERVAL_NANOS = 600_000_000_000L;
    private static final Set<String> ONCE_KEYS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<String, RateState> RATE_STATES = new ConcurrentHashMap<>();

    private VulkanBerylDebugLog() {
    }

    static void once(String key, String message) {
        if (ONCE_KEYS.add(key)) {
            info(message);
        }
    }

    static void verbose(String key, String message) {
        if (VERBOSE_LOGS) {
            info(message);
        }
    }

    static void verboseOnce(String key, String message) {
        if (VERBOSE_LOGS && ONCE_KEYS.add("verbose:" + key)) {
            info(message);
        }
    }

    static void trace(String key, String message) {
        if (TRACE_LOGS) {
            info(message);
        }
    }

    static void traceOnce(String key, String message) {
        if (TRACE_LOGS && ONCE_KEYS.add("trace:" + key)) {
            info(message);
        }
    }

    static void rateLimited(String key, String message, int intervalFrames) {
        if (shouldPrint(key, Math.max(1, intervalFrames), DEFAULT_WARN_INTERVAL_NANOS)) {
            info(withSuppressedCount(key, message));
        }
    }

    static void warnRateLimited(String key, String message) {
        if (shouldPrint(key, DEFAULT_WARN_INTERVAL_FRAMES, DEFAULT_WARN_INTERVAL_NANOS)) {
            Logger.warn(withSuppressedCount(key, prefixed(message)));
        }
    }

    static void stateLimited(String key, String message, String stateSnapshot) {
        if (containsSeriousDiagnostic(message) || shouldPrintState(key, stateSnapshot, DEFAULT_STATE_INTERVAL_FRAMES, DEFAULT_STATE_INTERVAL_NANOS)) {
            info(withSuppressedCount(key, message));
        }
    }

    static void error(String message) {
        Logger.error(prefixed(message));
    }

    static void always(String message) {
        info(message);
    }

    static void alwaysRaw(String message) {
        Logger.info(message);
    }

    private static void info(String message) {
        Logger.info(prefixed(message));
    }

    private static String prefixed(String message) {
        return message.startsWith(PREFIX) ? message : PREFIX + message;
    }

    private static boolean shouldPrint(String key, int intervalFrames, long intervalNanos) {
        RateState state = RATE_STATES.computeIfAbsent(key, ignored -> new RateState());
        long now = System.nanoTime();
        synchronized (state) {
            state.callsSincePrint++;
            if (!state.printed) {
                markPrinted(state, now);
                return true;
            }
            boolean frameReady = state.callsSincePrint >= intervalFrames;
            boolean timeReady = now - state.lastPrintNanos >= intervalNanos;
            if (frameReady || timeReady) {
                markPrinted(state, now);
                return true;
            }
            state.suppressedSincePrint++;
            return false;
        }
    }

    private static boolean shouldPrintState(String key, String stateSnapshot, int intervalFrames, long intervalNanos) {
        RateState state = RATE_STATES.computeIfAbsent(key, ignored -> new RateState());
        long now = System.nanoTime();
        synchronized (state) {
            state.callsSincePrint++;
            if (!state.printed || !stateSnapshot.equals(state.lastStateSnapshot)) {
                state.lastStateSnapshot = stateSnapshot;
                markPrinted(state, now);
                return true;
            }
            boolean frameReady = state.callsSincePrint >= intervalFrames;
            boolean timeReady = now - state.lastPrintNanos >= intervalNanos;
            if (frameReady || timeReady) {
                markPrinted(state, now);
                return true;
            }
            state.suppressedSincePrint++;
            return false;
        }
    }

    private static void markPrinted(RateState state, long now) {
        state.printed = true;
        state.lastSuppressedForPrint = state.suppressedSincePrint;
        state.callsSincePrint = 0;
        state.lastPrintNanos = now;
        state.suppressedSincePrint = 0;
    }

    private static boolean containsSeriousDiagnostic(String message) {
        return message.contains("VK_ERROR")
                || message.contains("DEVICE_LOST")
                || message.contains("Exception")
                || message.contains("ERROR");
    }

    private static String withSuppressedCount(String key, String message) {
        RateState state = RATE_STATES.get(key);
        long suppressed = state == null ? 0L : state.lastSuppressedForPrint;
        if (suppressed <= 0L) {
            return message;
        }
        return message + " (suppressed repeats=" + suppressed + ")";
    }

    private static final class RateState {
        private boolean printed;
        private int callsSincePrint;
        private long lastPrintNanos;
        private long suppressedSincePrint;
        private long lastSuppressedForPrint;
        private String lastStateSnapshot = "";
    }
}
