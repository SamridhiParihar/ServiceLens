package com.servicelens.chunking.processors;

import com.servicelens.chunking.CodeChunk;
import com.servicelens.chunking.FileProcessor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link FileProcessor} implementation for OpenAPI 3 and Swagger 2 specification files.
 *
 * <p>Processes YAML, YML, and JSON files that contain an OpenAPI or Swagger specification.
 * The file is detected by looking for the {@code openapi:}, {@code swagger:},
 * {@code "openapi"}, or {@code "swagger"} keywords in the file content. Actual parsing
 * is delegated to {@link OpenAPIV3Parser} from the Swagger Parser library.</p>
 *
 * <p>Each HTTP operation (GET, POST, PUT, DELETE) on each path is emitted as a separate
 * {@link CodeChunk} with type {@link CodeChunk.ChunkType#API_SPEC}, capturing the HTTP
 * method, path, summary, description, and operation ID. Operations that carry none of
 * these fields are skipped.</p>
 *
 * <p>This processor runs at priority 10, which is higher than the generic
 * {@link YamlFileProcessor} (priority 5), so OpenAPI YAML files are claimed here first.</p>
 */
@Component
public class OpenApiFileProcessor implements FileProcessor {

    private static final Logger log = LoggerFactory.getLogger(OpenApiFileProcessor.class);

    /**
     * Returns {@code true} if the file is a YAML, YML, or JSON file that appears
     * to contain an OpenAPI or Swagger specification.
     *
     * <p>Detection is done by reading the file content and checking for the presence
     * of the {@code openapi:}, {@code swagger:}, {@code "openapi"}, or
     * {@code "swagger"} strings (case-insensitive). Returns {@code false} if the
     * file cannot be read.</p>
     *
     * @param file the candidate file path
     * @return {@code true} if this processor should handle the file
     */
    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString();
        boolean isYamlOrJson = name.endsWith(".yaml") || name.endsWith(".yml")
                || name.endsWith(".json");
        if (!isYamlOrJson) return false;

        try {
            String content = java.nio.file.Files.readString(file);
            String lower = content.toLowerCase();
            return lower.contains("openapi:") || lower.contains("swagger:")
                    || lower.contains("\"openapi\"") || lower.contains("\"swagger\"");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the processing priority for this processor.
     *
     * <p>A higher priority than the generic YAML processor (10 vs 5) ensures that
     * OpenAPI YAML files are claimed by this processor before
     * {@link YamlFileProcessor} is consulted.</p>
     *
     * @return the priority value {@code 10}
     */
    @Override
    public int priority() {
        return 10;
    }

    /**
     * Parse the OpenAPI specification file and produce one {@link CodeChunk} per
     * HTTP operation.
     *
     * <p>For each path in the specification, the GET, POST, PUT, and DELETE operations
     * are inspected. An operation is only emitted as a chunk if it has at least one
     * of: summary, description, or operationId. The chunk content is a formatted
     * plain-text summary of the operation including all available fields.</p>
     *
     * @param file        the OpenAPI specification file to process
     * @param serviceName the logical service name to associate with produced chunks
     * @return list of {@link CodeChunk} instances, one per non-empty HTTP operation;
     *         empty if the file cannot be parsed or contains no paths
     */
    @Override
    public List<CodeChunk> process(Path file, String serviceName) {
        List<CodeChunk> chunks = new ArrayList<>();

        try {
            OpenAPI openAPI = new OpenAPIV3Parser().read(file.toString());
            if (openAPI == null || openAPI.getPaths() == null) return chunks;

            openAPI.getPaths().forEach((path, pathItem) -> {

                Map<String, Operation> operations = Map.of(
                        "GET", pathItem.getGet() != null ? pathItem.getGet() : new Operation(),
                        "POST", pathItem.getPost() != null ? pathItem.getPost() : new Operation(),
                        "PUT", pathItem.getPut() != null ? pathItem.getPut() : new Operation(),
                        "DELETE", pathItem.getDelete() != null ? pathItem.getDelete() : new Operation()
                );

                operations.forEach((method, operation) -> {
                    if (operation.getSummary() == null && operation.getDescription() == null
                            && operation.getOperationId() == null) return;

                    StringBuilder content = new StringBuilder();
                    content.append(method).append(" ").append(path).append("\n");
                    if (operation.getSummary() != null)
                        content.append("Summary: ").append(operation.getSummary()).append("\n");
                    if (operation.getDescription() != null)
                        content.append("Description: ").append(operation.getDescription()).append("\n");
                    if (operation.getOperationId() != null)
                        content.append("OperationId: ").append(operation.getOperationId()).append("\n");

                    chunks.add(new CodeChunk(
                            content.toString(),
                            file.toString(),
                            method + " " + path,
                            0, 0,
                            "openapi",
                            "OPENAPI",
                            CodeChunk.ChunkType.API_SPEC,
                            serviceName,
                            Map.of("http_method", method, "api_path", path)
                    ));
                });
            });

        } catch (Exception e) {
            log.warn("Could not parse OpenAPI file {}: {}", file.getFileName(), e.getMessage());
        }

        return chunks;
    }
}
