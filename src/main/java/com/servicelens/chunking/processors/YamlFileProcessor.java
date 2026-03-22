package com.servicelens.chunking.processors;

import com.servicelens.chunking.CodeChunk;
import com.servicelens.chunking.FileProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * {@link FileProcessor} implementation for YAML configuration files.
 *
 * <p>Processes {@code .yml} and {@code .yaml} files that are not OpenAPI/Swagger
 * specifications (those are handled by {@link OpenApiFileProcessor} at higher priority).
 * The processor parses each YAML file using SnakeYAML and creates one
 * {@link CodeChunk} per top-level configuration key, so each distinct concern
 * (e.g. {@code spring}, {@code server}, {@code management}) becomes a separately
 * retrievable chunk with type {@link CodeChunk.ChunkType#CONFIG}.</p>
 *
 * <p>This processor runs at priority 5, which is lower than the OpenAPI processor
 * (priority 10), so OpenAPI YAML files are claimed first.</p>
 */
@Component
public class YamlFileProcessor implements FileProcessor {

    private static final Logger log = LoggerFactory.getLogger(YamlFileProcessor.class);

    private static final Set<String> OPENAPI_MARKERS = Set.of("openapi", "swagger");

    /**
     * Returns {@code true} if the file has a {@code .yml} or {@code .yaml} extension
     * and does not appear to be an OpenAPI specification file.
     *
     * @param file the candidate file path
     * @return {@code true} if this processor should handle the file
     */
    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString();
        return (name.endsWith(".yml") || name.endsWith(".yaml"))
                && !isOpenApiFile(file);
    }

    /**
     * Returns the processing priority for this processor.
     *
     * <p>A lower value than the OpenAPI processor (5 vs 10) ensures that YAML
     * files that are OpenAPI specs are claimed by {@link OpenApiFileProcessor}
     * before this processor is consulted.</p>
     *
     * @return the priority value {@code 5}
     */
    @Override
    public int priority() {
        return 5;
    }

    /**
     * Parse the YAML file and produce one {@link CodeChunk} per top-level key.
     *
     * <p>Each chunk's content is the key plus its value subtree formatted as
     * indented YAML text. The chunk carries {@code config_key} and
     * {@code config_file} extra metadata entries.</p>
     *
     * @param file        the YAML file to process
     * @param serviceName the logical service name to associate with produced chunks
     * @return list of {@link CodeChunk} instances, one per top-level YAML key;
     *         empty if the file is blank, cannot be read, or fails to parse
     */
    @Override
    public List<CodeChunk> process(Path file, String serviceName) {
        List<CodeChunk> chunks = new ArrayList<>();

        try {
            String content = Files.readString(file);
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(content);

            if (data == null) return chunks;

            data.forEach((key, value) -> {
                String chunkContent = key + ":\n" + formatValue(value, 1);
                chunks.add(new CodeChunk(
                        chunkContent,
                        file.toString(),
                        key,
                        0, 0,
                        "yaml",
                        "YAML",
                        CodeChunk.ChunkType.CONFIG,
                        serviceName,
                        Map.of("config_key", key,
                                "config_file", file.getFileName().toString())
                ));
            });

        } catch (IOException e) {
            log.warn("Could not read YAML file {}: {}", file.getFileName(), e.getMessage());
        }

        return chunks;
    }

    /**
     * Determine whether the file is an OpenAPI or Swagger specification by checking
     * for well-known marker strings in the first 200 characters of the file.
     *
     * @param file the file to inspect
     * @return {@code true} if the file contains an {@code openapi:} or {@code swagger:} key
     */
    private boolean isOpenApiFile(Path file) {
        try {
            String firstLines = Files.readString(file).substring(0, Math.min(200,
                    (int) Files.size(file)));
            return OPENAPI_MARKERS.stream()
                    .anyMatch(marker -> firstLines.toLowerCase().contains(marker + ":"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Recursively format a YAML value as indented text for inclusion in chunk content.
     *
     * @param value  the YAML value object (may be a {@link Map}, {@link List}, or scalar)
     * @param indent the current indentation depth (each level adds two spaces)
     * @return a formatted string representation of the value
     */
    private String formatValue(Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            map.forEach((k, v) -> {
                sb.append("  ".repeat(indent)).append(k).append(": ");
                if (v instanceof Map || v instanceof List) {
                    sb.append("\n").append(formatValue(v, indent + 1));
                } else {
                    sb.append(v).append("\n");
                }
            });
            return sb.toString();
        }
        return String.valueOf(value) + "\n";
    }
}
