package me.cortex.voxy.client.core.rendering.section.backend.vulkanberyl;

import net.minecraft.resources.Identifier;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
        ImportResolution resolution = new ImportResolution();
        String expandedSource = resolution.expandRoot(rootShader);

        Path root;
        try {
            root = Files.createTempDirectory("voxy-vulkanberyl-shaders-");
            root.toFile().deleteOnExit();
            Path shaderPath = root.resolve(rootShader.getPath() + ".comp");
            Files.createDirectories(shaderPath.getParent());
            Files.writeString(shaderPath, expandedSource, StandardCharsets.UTF_8);
            shaderPath.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write preprocessed shader for " + shaderResourceId, e);
        }

        return new PreparedShader(root.toUri().toString(), rootShader.getPath());
    }

    record PreparedShader(String rootUrl, String shaderName) {}

    private static final class ImportResolution {
        private final Set<Identifier> onceIncluded = new LinkedHashSet<>();
        private final ArrayDeque<Identifier> includeStack = new ArrayDeque<>();

        String expandRoot(Identifier shader) {
            String src = loadShaderAsset(shader);
            StringBuilder out = new StringBuilder();
            String[] lines = src.split("\\R", -1);
            int startLine = 0;
            while (startLine < lines.length && lines[startLine].trim().isEmpty()) startLine++;
            if (startLine < lines.length && lines[startLine].startsWith("#version")) {
                out.append(lines[startLine]).append('\n');
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
                    out.append(line).append('\n');
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
            String path = "/assets/" + id.getNamespace() + "/shaders/" + id.getPath();
            try (InputStream in = ImportResolution.class.getResourceAsStream(path)) {
                if (in == null) throw new IllegalStateException("Shader import not found: " + id + " (" + path + ")");
                return IOUtils.toString(in, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed reading shader import " + id + " (" + path + ")", e);
            }
        }
    }
}
