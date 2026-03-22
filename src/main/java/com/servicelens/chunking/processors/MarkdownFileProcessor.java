package com.servicelens.chunking.processors;

import com.servicelens.chunking.CodeChunk;
import com.servicelens.chunking.FileProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link FileProcessor} implementation for Markdown documentation files.
 *
 * <p>Processes {@code .md} and {@code .markdown} files by splitting content at
 * second-level headings ({@code ## }). Each resulting section is stored as a
 * separate {@link CodeChunk}, keeping related paragraphs together for coherent
 * semantic retrieval.</p>
 *
 * <p>The chunk type is determined heuristically from the file name:</p>
 * <ul>
 *   <li>Files whose name contains {@code "context"} or {@code "business"} are
 *       classified as {@link CodeChunk.ChunkType#BUSINESS_CONTEXT}, which allows
 *       the retrieval layer to target domain-rule queries specifically.</li>
 *   <li>All other Markdown files are classified as
 *       {@link CodeChunk.ChunkType#DOCUMENTATION}.</li>
 * </ul>
 */
@Component
public class MarkdownFileProcessor implements FileProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarkdownFileProcessor.class);

    /**
     * Returns {@code true} if the file has a {@code .md} or {@code .markdown} extension.
     *
     * @param file the candidate file path
     * @return {@code true} if this processor should handle the file
     */
    @Override
    public boolean supports(Path file) {
        return file.toString().endsWith(".md") || file.toString().endsWith(".markdown");
    }

    /**
     * Parse the Markdown file and produce one {@link CodeChunk} per second-level section.
     *
     * <p>Content before the first {@code ## } heading is gathered under the implicit
     * section name {@code "Introduction"}. Each subsequent {@code ## } heading starts
     * a new section. The final section is flushed after the last line is processed.</p>
     *
     * @param file        the Markdown file to process
     * @param serviceName the logical service name to associate with produced chunks
     * @return list of {@link CodeChunk} instances, one per Markdown section;
     *         empty if the file is blank or cannot be read
     */
    @Override
    public List<CodeChunk> process(Path file, String serviceName) {
        List<CodeChunk> chunks = new ArrayList<>();

        try {
            String content = Files.readString(file);
            String[] lines = content.split("\n");

            String currentHeader = "Introduction";
            StringBuilder currentContent = new StringBuilder();
            int sectionStartLine = 1;
            int lineNum = 0;

            for (String line : lines) {
                lineNum++;
                if (line.startsWith("## ")) {
                    if (!currentContent.isEmpty()) {
                        chunks.add(buildChunk(file, serviceName, currentHeader,
                                currentContent.toString(), sectionStartLine, lineNum - 1));
                    }
                    currentHeader = line.substring(3).trim();
                    currentContent = new StringBuilder();
                    sectionStartLine = lineNum;
                } else {
                    currentContent.append(line).append("\n");
                }
            }

            if (!currentContent.isEmpty()) {
                chunks.add(buildChunk(file, serviceName, currentHeader,
                        currentContent.toString(), sectionStartLine, lineNum));
            }

        } catch (IOException e) {
            log.warn("Could not read Markdown file {}: {}", file.getFileName(), e.getMessage());
        }

        return chunks;
    }

    /**
     * Build a single {@link CodeChunk} for one Markdown section.
     *
     * <p>The chunk type is {@link CodeChunk.ChunkType#BUSINESS_CONTEXT} if the file
     * name contains {@code "context"} or {@code "business"} (case-insensitive),
     * otherwise {@link CodeChunk.ChunkType#DOCUMENTATION}.</p>
     *
     * @param file        the source Markdown file
     * @param serviceName the logical service name
     * @param header      the section heading text (without the {@code ## } prefix)
     * @param content     the section body text
     * @param start       1-based start line of this section
     * @param end         1-based end line of this section
     * @return a fully populated {@link CodeChunk} for this section
     */
    private CodeChunk buildChunk(Path file, String serviceName, String header,
                                 String content, int start, int end) {
        boolean isBusinessContext = file.getFileName().toString()
                .toLowerCase().contains("context") ||
                file.getFileName().toString().toLowerCase().contains("business");

        return new CodeChunk(
                "## " + header + "\n" + content,
                file.toString(),
                header,
                start, end,
                "markdown",
                "MARKDOWN",
                isBusinessContext
                        ? CodeChunk.ChunkType.BUSINESS_CONTEXT
                        : CodeChunk.ChunkType.DOCUMENTATION,
                serviceName,
                Map.of("section_title", header)
        );
    }
}
