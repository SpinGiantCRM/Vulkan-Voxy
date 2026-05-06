package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

final class VulkanBerylLodBringupDiagnostics {
    private static String lastSummary = "";

    private static boolean smokeNoop;
    private static boolean shaderSmoke;
    private static boolean uniformSmoke;
    private static int realTraversalStageLimit;
    private static boolean controlledRenderList;
    private static boolean safeSectionFound;
    private static boolean cmdgenEnabled;
    private static boolean cmdgenSampleValid;
    private static boolean indirectDrawEnabled;
    private static int lastVisibleCount = -1;
    private static int lastSafeSectionId = -1;
    private static String reasonLodNotVisible = "startup";

    private VulkanBerylLodBringupDiagnostics() {}

    static synchronized void updateRuntime(boolean smokeNoop,
                                           boolean shaderSmoke,
                                           boolean uniformSmoke,
                                           int realTraversalStageLimit,
                                           boolean controlledRenderList,
                                           boolean cmdgenEnabled,
                                           boolean indirectDrawEnabled,
                                           int lastVisibleCount,
                                           String reason) {
        VulkanBerylLodBringupDiagnostics.smokeNoop = smokeNoop;
        VulkanBerylLodBringupDiagnostics.shaderSmoke = shaderSmoke;
        VulkanBerylLodBringupDiagnostics.uniformSmoke = uniformSmoke;
        VulkanBerylLodBringupDiagnostics.realTraversalStageLimit = realTraversalStageLimit;
        VulkanBerylLodBringupDiagnostics.controlledRenderList = controlledRenderList;
        VulkanBerylLodBringupDiagnostics.cmdgenEnabled = cmdgenEnabled;
        VulkanBerylLodBringupDiagnostics.indirectDrawEnabled = indirectDrawEnabled;
        VulkanBerylLodBringupDiagnostics.lastVisibleCount = lastVisibleCount;
        if (reason != null && !reason.isBlank()) {
            VulkanBerylLodBringupDiagnostics.reasonLodNotVisible = reason;
        }
        logIfChanged();
    }

    static synchronized void updateControlledRenderList(boolean safeSectionFound, int lastSafeSectionId, String reason) {
        VulkanBerylLodBringupDiagnostics.safeSectionFound = safeSectionFound;
        VulkanBerylLodBringupDiagnostics.lastSafeSectionId = lastSafeSectionId;
        if (reason != null && !reason.isBlank()) {
            VulkanBerylLodBringupDiagnostics.reasonLodNotVisible = reason;
        }
        logIfChanged();
    }

    static synchronized void updateCmdgenSample(boolean cmdgenSampleValid, String reason) {
        VulkanBerylLodBringupDiagnostics.cmdgenSampleValid = cmdgenSampleValid;
        if (reason != null && !reason.isBlank()) {
            VulkanBerylLodBringupDiagnostics.reasonLodNotVisible = reason;
        }
        logIfChanged();
    }

    private static void logIfChanged() {
        String summary = "LOD bring-up summary: smokeNoop=" + smokeNoop
                + " shaderSmoke=" + shaderSmoke
                + " uniformSmoke=" + uniformSmoke
                + " realTraversalStageLimit=" + realTraversalStageLimit
                + " controlledRenderList=" + controlledRenderList
                + " safeSectionFound=" + safeSectionFound
                + " cmdgenEnabled=" + cmdgenEnabled
                + " cmdgenSampleValid=" + cmdgenSampleValid
                + " indirectDrawEnabled=" + indirectDrawEnabled
                + " lastVisibleCount=" + lastVisibleCount
                + " lastSafeSectionId=" + lastSafeSectionId
                + " reason=" + reasonLodNotVisible;
        if (!summary.equals(lastSummary)) {
            VulkanBerylDebugLog.rateLimited("lod-bringup-summary", summary, 120);
            lastSummary = summary;
        }
    }
}
