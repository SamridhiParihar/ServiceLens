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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link FileProcessor} implementation for SQL schema and migration files.
 *
 * <p>Processes {@code .sql} files by extracting individual SQL statements using
 * a regex pattern that matches common DDL and DML statement types (CREATE TABLE,
 * ALTER TABLE, DROP, INSERT, UPDATE, SELECT). Each statement is stored as a
 * separate {@link CodeChunk} with type {@link CodeChunk.ChunkType#SCHEMA}.</p>
 *
 * <p>Approximate line numbers are computed by counting newline characters in the
 * content string up to the match boundaries. The element name and statement type
 * are extracted from the statement text for use as metadata.</p>
 */
@Component
public class SqlFileProcessor implements FileProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqlFileProcessor.class);

    private static final Pattern STATEMENT_PATTERN = Pattern.compile(
            "(CREATE\\s+(?:TABLE|INDEX|VIEW|PROCEDURE|FUNCTION|TRIGGER)|" +
                    "ALTER\\s+TABLE|DROP\\s+(?:TABLE|INDEX|VIEW)|" +
                    "INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|" +
                    "SELECT\\b)[^;]+;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * Returns {@code true} if the file has a {@code .sql} extension.
     *
     * @param file the candidate file path
     * @return {@code true} if this processor should handle the file
     */
    @Override
    public boolean supports(Path file) {
        return file.toString().endsWith(".sql");
    }

    /**
     * Parse the SQL file and produce one {@link CodeChunk} per matched SQL statement.
     *
     * <p>Statements are detected using {@code STATEMENT_PATTERN}. For each match,
     * the element name (table/view/procedure name) and statement type are extracted
     * as metadata. Approximate start and end line numbers are computed by counting
     * newlines before the match boundaries.</p>
     *
     * @param file        the SQL file to process
     * @param serviceName the logical service name to associate with produced chunks
     * @return list of {@link CodeChunk} instances, one per matched SQL statement;
     *         empty if the file is blank, unreadable, or contains no matching statements
     */
    @Override
    public List<CodeChunk> process(Path file, String serviceName) {
        List<CodeChunk> chunks = new ArrayList<>();

        try {
            String content = Files.readString(file);
            Matcher matcher = STATEMENT_PATTERN.matcher(content);

            while (matcher.find()) {
                String statement = matcher.group().trim();
                String elementName = extractElementName(statement);
                String statementType = extractStatementType(statement);

                int startLine = countLines(content, 0, matcher.start());
                int endLine = countLines(content, 0, matcher.end());

                chunks.add(new CodeChunk(
                        statement,
                        file.toString(),
                        elementName,
                        startLine,
                        endLine,
                        "sql",
                        "SQL",
                        CodeChunk.ChunkType.SCHEMA,
                        serviceName,
                        Map.of("statement_type", statementType)
                ));
            }

        } catch (IOException e) {
            log.warn("Could not read SQL file {}: {}", file.getFileName(), e.getMessage());
        }

        return chunks;
    }

    /**
     * Extract the primary object name (table, view, procedure, or target of INSERT/UPDATE)
     * from a SQL statement.
     *
     * @param statement the SQL statement text
     * @return the extracted object name, or {@code "unknown"} if no name could be identified
     */
    private String extractElementName(String statement) {
        Pattern namePattern = Pattern.compile(
                "(?:TABLE|VIEW|PROCEDURE|FUNCTION|INDEX|INTO|UPDATE)\\s+(\\w+)",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = namePattern.matcher(statement);
        return m.find() ? m.group(1) : "unknown";
    }

    /**
     * Classify a SQL statement into a high-level statement type label.
     *
     * @param statement the SQL statement text (leading whitespace is ignored)
     * @return one of {@code "CREATE_TABLE"}, {@code "CREATE_INDEX"},
     *         {@code "STORED_PROCEDURE"}, {@code "ALTER_TABLE"}, {@code "INSERT"},
     *         or {@code "OTHER"}
     */
    private String extractStatementType(String statement) {
        String upper = statement.trim().toUpperCase();
        if (upper.startsWith("CREATE TABLE")) return "CREATE_TABLE";
        if (upper.startsWith("CREATE INDEX")) return "CREATE_INDEX";
        if (upper.startsWith("CREATE PROCEDURE")) return "STORED_PROCEDURE";
        if (upper.startsWith("ALTER TABLE")) return "ALTER_TABLE";
        if (upper.startsWith("INSERT")) return "INSERT";
        return "OTHER";
    }

    /**
     * Count the number of lines (1-based) in the substring of {@code text}
     * from index {@code from} up to (exclusive) index {@code to}.
     *
     * @param text the full file content string
     * @param from the start offset in the string (inclusive)
     * @param to   the end offset in the string (exclusive)
     * @return the 1-based line count at position {@code to}
     */
    private int countLines(String text, int from, int to) {
        int count = 1;
        for (int i = from; i < to && i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
