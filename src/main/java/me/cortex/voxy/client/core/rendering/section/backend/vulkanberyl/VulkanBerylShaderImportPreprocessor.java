package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import net.minecraft.resources.Identifier;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VulkanBerylShaderImportPreprocessor {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*#import\\s*<(?<namespace>[^:>]+):(?<path>[^>]+)>\\s*$");

    private VulkanBerylShaderImportPreprocessor() {
    }

    static PreparedShader preprocessToTemp(String shaderResourceId) {
        Identifier rootShader = Identifier.parse(shaderResourceId);
        assertNormalizationInvariants();
        ImportResolution resolution = new ImportResolution();
        String expandedSource = resolution.expandRoot(rootShader);
        String shaderName = outputShaderRelativePath(rootShader);
        String outputShaderRelativePath = shaderName + ".comp";

        Path root;
        Path shaderPath;
        try {
            root = Files.createTempDirectory("voxy-vulkanberyl-shaders-");
            root.toFile().deleteOnExit();
            shaderPath = root.resolve(outputShaderRelativePath);
            Files.createDirectories(shaderPath.getParent());
            Files.writeString(shaderPath, expandedSource, StandardCharsets.UTF_8);
            shaderPath.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write preprocessed shader for " + shaderResourceId, e);
        }

        String relativePathFromName = shaderName + ".comp";
        if (!outputShaderRelativePath.equals(relativePathFromName)) {
            throw new IllegalStateException("Preprocessed shader path invariant failed: expected " + relativePathFromName + " but got " + outputShaderRelativePath);
        }
        long shaderFileSize;
        try {
            shaderFileSize = Files.size(shaderPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading preprocessed shader size for " + shaderResourceId + " at " + shaderPath, e);
        }
        String rootUrl = root.toUri().toString();
        VulkanBerylDebugLog.once("shader-preprocess:" + shaderResourceId, "Prepared shader import preprocess: shaderResourceId=" + shaderResourceId
                + ", classpathInputPath=" + classpathShaderAssetPath(rootShader)
                + ", tempRootPath=" + root
                + ", tempShaderRelativePath=" + outputShaderRelativePath
                + ", compileShaderName=" + shaderName
                + ", outputBytes=" + shaderFileSize
                + ", rootUrl=" + rootUrl);
        return new PreparedShader(rootUrl, shaderName, shaderPath, outputShaderRelativePath, shaderFileSize);
    }

    static PreparedShaderSet preprocessShaderSetToTemp(String... shaderResourceIds) {
        if (shaderResourceIds == null || shaderResourceIds.length == 0) {
            throw new IllegalArgumentException("shaderResourceIds must not be empty");
        }
        assertNormalizationInvariants();
        ImportResolution resolution = new ImportResolution();
        Path root;
        List<PreparedShader> preparedShaders = new ArrayList<>(shaderResourceIds.length);
        try {
            root = Files.createTempDirectory("voxy-vulkanberyl-shaders-");
            root.toFile().deleteOnExit();
            for (String shaderResourceId : shaderResourceIds) {
                Identifier rootShader = Identifier.parse(shaderResourceId);
                String expandedSource = resolution.expandRoot(rootShader);
                String shaderName = outputShaderRelativePath(rootShader);
                String extension = extractExtension(rootShader.getPath());
                String outputShaderRelativePath = shaderName + extension;
                assertShaderPathInvariants(shaderResourceId, shaderName, outputShaderRelativePath, extension);
                Path shaderPath = root.resolve(outputShaderRelativePath);
                Files.createDirectories(shaderPath.getParent());
                Files.writeString(shaderPath, expandedSource, StandardCharsets.UTF_8);
                shaderPath.toFile().deleteOnExit();
                long shaderFileSize = Files.size(shaderPath);
                preparedShaders.add(new PreparedShader(root.toUri().toString(), shaderName, shaderPath, outputShaderRelativePath, shaderFileSize));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write preprocessed shader set", e);
        }
        String rootUrl = root.toUri().toString();
        for (PreparedShader shader : preparedShaders) {
            VulkanBerylDebugLog.once("shader-preprocess:" + shaderResourceIds[preparedShaders.indexOf(shader)], "Prepared shader import preprocess: shaderResourceId="
                    + shaderResourceIds[preparedShaders.indexOf(shader)]
                    + ", tempRootPath=" + root
                    + ", tempShaderRelativePath=" + shader.tempShaderRelativePath()
                    + ", compileShaderName=" + shader.shaderName()
                    + ", outputBytes=" + shader.outputBytes()
                    + ", rootUrl=" + rootUrl);
        }
        return new PreparedShaderSet(rootUrl, root, List.copyOf(preparedShaders));
    }

    record PreparedShader(String rootUrl, String shaderName, Path shaderPath, String tempShaderRelativePath, long outputBytes) {}
    record PreparedShaderSet(String rootUrl, Path tempRootPath, List<PreparedShader> shaders) {}

    static String classpathShaderAssetPath(Identifier id) {
        String path = id.getPath();
        String normalizedPath = path.startsWith("shaders/") ? path : "shaders/" + path;
        return "/assets/" + id.getNamespace() + "/" + normalizedPath;
    }

    static String outputShaderRelativePath(Identifier rootShader) {
        String path = rootShader.getPath();
        String relativePath = path.startsWith("shaders/") ? path.substring("shaders/".length()) : path;
        return stripKnownShaderExtension(relativePath);
    }

    private static String stripKnownShaderExtension(String path) {
        for (String ext : List.of(".comp", ".vsh", ".fsh")) {
            if (path.endsWith(ext)) {
                return path.substring(0, path.length() - ext.length());
            }
        }
        return path;
    }

    private static String extractExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            throw new IllegalStateException("Shader path is missing extension: " + path);
        }
        return path.substring(dot);
    }

    private static void assertShaderPathInvariants(String shaderResourceId, String shaderName, String tempShaderRelativePath, String extension) {
        if ("voxy:shaders/vulkanberyl/section/draw.vsh".equals(shaderResourceId)) {
            if (!"vulkanberyl/section/draw".equals(shaderName)) {
                throw new IllegalStateException("Draw vertex shaderName invariant failed: " + shaderName);
            }
            if (!"vulkanberyl/section/draw.vsh".equals(tempShaderRelativePath)) {
                throw new IllegalStateException("Draw vertex temp path invariant failed: " + tempShaderRelativePath);
            }
        }
        if ("voxy:shaders/vulkanberyl/section/draw.fsh".equals(shaderResourceId)) {
            if (!"vulkanberyl/section/draw".equals(shaderName)) {
                throw new IllegalStateException("Draw fragment shaderName invariant failed: " + shaderName);
            }
            if (!"vulkanberyl/section/draw.fsh".equals(tempShaderRelativePath)) {
                throw new IllegalStateException("Draw fragment temp path invariant failed: " + tempShaderRelativePath);
            }
        }
        if ("voxy:shaders/vulkanberyl/hierarchical/traversal.comp".equals(shaderResourceId)) {
            if (!"vulkanberyl/hierarchical/traversal".equals(shaderName)) {
                throw new IllegalStateException("Traversal shaderName invariant failed: " + shaderName);
            }
            if (!"vulkanberyl/hierarchical/traversal.comp".equals(tempShaderRelativePath)) {
                throw new IllegalStateException("Traversal temp path invariant failed: " + tempShaderRelativePath);
            }
        }
        String expectedTempPath = shaderName + extension;
        if (!expectedTempPath.equals(tempShaderRelativePath)) {
            throw new IllegalStateException("Shader temp path invariant failed: expected " + expectedTempPath + " but got " + tempShaderRelativePath + " for " + shaderResourceId);
        }
    }

    private static void assertNormalizationInvariants() {
        Identifier root = Identifier.parse("voxy:shaders/vulkanberyl/hierarchical/traversal.comp");
        Identifier imported = Identifier.parse("voxy:lod/frustum.glsl");
        Identifier drawVertex = Identifier.parse("voxy:shaders/vulkanberyl/section/draw.vsh");
        Identifier drawFragment = Identifier.parse("voxy:shaders/vulkanberyl/section/draw.fsh");
        String rootPath = classpathShaderAssetPath(root);
        String importPath = classpathShaderAssetPath(imported);
        if (!"/assets/voxy/shaders/vulkanberyl/hierarchical/traversal.comp".equals(rootPath)) {
            throw new IllegalStateException("Root shader path normalization failed: " + root + " -> " + rootPath);
        }
        if (!"/assets/voxy/shaders/lod/frustum.glsl".equals(importPath)) {
            throw new IllegalStateException("Import shader path normalization failed: " + imported + " -> " + importPath);
        }
        assertShaderPathInvariants(root.toString(), outputShaderRelativePath(root), outputShaderRelativePath(root) + ".comp", ".comp");
        assertShaderPathInvariants(drawVertex.toString(), outputShaderRelativePath(drawVertex), outputShaderRelativePath(drawVertex) + ".vsh", ".vsh");
        assertShaderPathInvariants(drawFragment.toString(), outputShaderRelativePath(drawFragment), outputShaderRelativePath(drawFragment) + ".fsh", ".fsh");
    }


    private static String normalizeVulkanVersionDirective(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if ("#version 460 core".equals(trimmed)) {
            return line.replace("#version 460 core", "#version 460");
        }
        if ("#version 450 core".equals(trimmed)) {
            return line.replace("#version 450 core", "#version 450");
        }
        return line;
    }

    private static boolean isGraphicsRootShader(Identifier shader) {
        String path = shader.getPath();
        return path.endsWith(".vsh") || path.endsWith(".fsh");
    }

    private static String shaderStageForGraphicsRoot(Identifier shader) {
        String path = shader.getPath();
        if (path.endsWith(".vsh")) {
            return "vertex";
        }
        if (path.endsWith(".fsh")) {
            return "fragment";
        }
        throw new IllegalStateException("Unexpected graphics shader extension for " + shader);
    }
    private static final class ImportResolution {
        private final Set<Identifier> onceIncluded = new LinkedHashSet<>();
        private final ArrayDeque<Identifier> includeStack = new ArrayDeque<>();

        String expandRoot(Identifier shader) {
            String src = loadShaderAsset(shader);
            StringBuilder out = new StringBuilder();
            String[] lines = src.split("\\R", -1);
            int startLine = 0;
            while (startLine < lines.length && lines[startLine].trim().isEmpty()) startLine++;
            boolean graphicsRoot = isGraphicsRootShader(shader);
            if (graphicsRoot) {
                out.append("#version 450\n");
                out.append("#pragma shader_stage(").append(shaderStageForGraphicsRoot(shader)).append(")\n");
                if (startLine < lines.length && lines[startLine].trim().startsWith("#version")) {
                    startLine++;
                }
            } else if (startLine < lines.length && lines[startLine].startsWith("#version")) {
                out.append(normalizeVulkanVersionDirective(lines[startLine])).append('\n');
                startLine++;
            } else {
                out.append("#version 460\n");
            }
            out.append("// begin root ").append(shader).append('\n');
            includeStack.push(shader);
            expandLines(shader, lines, startLine, out, false);
            includeStack.pop();
            out.append("// end root ").append(shader).append('\n');
            return out.toString();
        }

        private void expandImport(Identifier importer, Identifier imported, StringBuilder out) {
            if (includeStack.contains(imported)) {
                throw new IllegalStateException("Shader import cycle detected: " + includeTrace(imported));
            }
            if (!onceIncluded.add(imported)) {
                out.append("// skip duplicate import ").append(imported).append(" (from ").append(importer).append(")\n");
                return;
            }
            out.append("// begin import ").append(imported).append(" (from ").append(importer).append(")\n");
            String[] lines = loadShaderAsset(imported).split("\\R", -1);
            includeStack.push(imported);
            expandLines(imported, lines, 0, out, true);
            includeStack.pop();
            out.append("// end import ").append(imported).append('\n');
        }

        private void expandLines(Identifier owner, String[] lines, int startLine, StringBuilder out, boolean skipVersion) {
            for (int i = startLine; i < lines.length; i++) {
                String line = lines[i];
                if (skipVersion && line.trim().startsWith("#version")) {
                    continue;
                }
                Matcher matcher = IMPORT_PATTERN.matcher(line);
                if (matcher.matches()) {
                    Identifier imported = Identifier.fromNamespaceAndPath(matcher.group("namespace"), matcher.group("path"));
                    expandImport(owner, imported, out);
                } else {
                    out.append(normalizeVulkanVersionDirective(line)).append('\n');
                }
            }
        }

        private String includeTrace(Identifier repeated) {
            ArrayDeque<Identifier> trace = new ArrayDeque<>(includeStack);
            StringBuilder out = new StringBuilder();
            boolean started = false;
            var it = trace.descendingIterator();
            while (it.hasNext()) {
                Identifier step = it.next();
                if (!started && !step.equals(repeated)) {
                    continue;
                }
                if (out.length() > 0) out.append(" -> ");
                out.append(step);
                started = true;
            }
            if (out.length() > 0) out.append(" -> ");
            out.append(repeated);
            return out.toString();
        }

        private static String loadShaderAsset(Identifier id) {
            String path = classpathShaderAssetPath(id);
            try (InputStream in = ImportResolution.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("Shader import not found: " + id + " (" + path + ")");
                return IOUtils.toString(in, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed reading shader import " + id + " (" + path + ")", e);
            }
        }
    }
}
