package com.servicelens.chunking;

import java.nio.file.Path;
import java.util.List;

public interface FileProcessor {

    /**
     * Returns true if this processor can handle the given file
     */
    boolean supports(Path file);

    /**
     * Process the file and return chunks
     */
    List<CodeChunk> process(Path file, String serviceName);

    /**
     * Priority - higher priority processors run first when multiple match
     */
    default int priority() {
        return 0;
    }
}