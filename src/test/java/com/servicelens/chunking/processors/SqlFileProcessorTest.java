package com.servicelens.chunking.processors;

import com.servicelens.chunking.CodeChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SqlFileProcessor}.
 *
 * <p>Real SQL files are written to a {@link TempDir}. Tests verify statement
 * extraction, chunk type, element-name parsing, and statement-type metadata.</p>
 */
@DisplayName("SqlFileProcessor")
class SqlFileProcessorTest {

    private SqlFileProcessor processor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        processor = new SqlFileProcessor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // supports()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("supports()")
    class SupportsTests {

        @Test
        @DisplayName("Returns true for .sql files")
        void trueForSql() {
            assertThat(processor.supports(Path.of("schema.sql"))).isTrue();
        }

        @Test
        @DisplayName("Returns false for .java files")
        void falseForJava() {
            assertThat(processor.supports(Path.of("Service.java"))).isFalse();
        }

        @Test
        @DisplayName("Returns false for .yml files")
        void falseForYml() {
            assertThat(processor.supports(Path.of("config.yml"))).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // process()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("process()")
    class ProcessTests {

        @Test
        @DisplayName("Extracts one chunk per CREATE TABLE statement")
        void extractsCreateTableStatements() throws IOException {
            Path f = tempDir.resolve("schema.sql");
            Files.writeString(f, """
                    CREATE TABLE orders (
                        id BIGSERIAL PRIMARY KEY,
                        status VARCHAR(50) NOT NULL
                    );

                    CREATE TABLE payments (
                        id BIGSERIAL PRIMARY KEY,
                        order_id BIGINT NOT NULL
                    );
                    """);

            List<CodeChunk> chunks = processor.process(f, "order-svc");

            assertThat(chunks).hasSize(2);
        }

        @Test
        @DisplayName("Every chunk has ChunkType.SCHEMA")
        void allChunksAreSchemaType() throws IOException {
            Path f = tempDir.resolve("schema.sql");
            Files.writeString(f, "CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(100));\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).extracting(CodeChunk::chunkType)
                    .containsOnly(CodeChunk.ChunkType.SCHEMA);
        }

        @Test
        @DisplayName("Extracts table name as element name for CREATE TABLE")
        void extractsTableNameAsElementName() throws IOException {
            Path f = tempDir.resolve("schema.sql");
            Files.writeString(f, "CREATE TABLE order_items (id BIGINT PRIMARY KEY);\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks.get(0).elementName()).isEqualTo("order_items");
        }

        @Test
        @DisplayName("statement_type metadata is CREATE_TABLE for CREATE TABLE statements")
        void statementTypeIsCreateTable() throws IOException {
            Path f = tempDir.resolve("schema.sql");
            Files.writeString(f, "CREATE TABLE payments (id BIGINT PRIMARY KEY);\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks.get(0).extraMetadata().get("statement_type"))
                    .isEqualTo("CREATE_TABLE");
        }

        @Test
        @DisplayName("statement_type metadata is ALTER_TABLE for ALTER TABLE statements")
        void statementTypeIsAlterTable() throws IOException {
            Path f = tempDir.resolve("migration.sql");
            Files.writeString(f,
                    "ALTER TABLE orders ADD COLUMN created_at TIMESTAMP DEFAULT NOW();\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.get(0).extraMetadata().get("statement_type"))
                    .isEqualTo("ALTER_TABLE");
        }

        @Test
        @DisplayName("statement_type metadata is CREATE_INDEX for CREATE INDEX statements")
        void statementTypeIsCreateIndex() throws IOException {
            Path f = tempDir.resolve("indexes.sql");
            Files.writeString(f,
                    "CREATE INDEX idx_orders_status ON orders (status);\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.get(0).extraMetadata().get("statement_type"))
                    .isEqualTo("CREATE_INDEX");
        }

        @Test
        @DisplayName("Chunk content contains the full SQL statement")
        void chunkContentContainsFullStatement() throws IOException {
            Path f = tempDir.resolve("schema.sql");
            Files.writeString(f,
                    "CREATE TABLE inventory (id BIGINT PRIMARY KEY, quantity INT);\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks.get(0).content()).contains("CREATE TABLE inventory");
            assertThat(chunks.get(0).content()).contains("quantity INT");
        }

        @Test
        @DisplayName("Service name is set on every chunk")
        void serviceNameSetOnEveryChunk() throws IOException {
            Path f = tempDir.resolve("schema.sql");
            Files.writeString(f, """
                    CREATE TABLE a (id BIGINT PRIMARY KEY);
                    CREATE TABLE b (id BIGINT PRIMARY KEY);
                    """);

            List<CodeChunk> chunks = processor.process(f, "warehouse-svc");

            assertThat(chunks).extracting(CodeChunk::serviceName)
                    .containsOnly("warehouse-svc");
        }

        @Test
        @DisplayName("Returns empty list for file with no matching SQL statements")
        void returnsEmptyForFileWithNoMatchingStatements() throws IOException {
            Path f = tempDir.resolve("comments.sql");
            Files.writeString(f, "-- Just a comment, no statements here\n");

            List<CodeChunk> chunks = processor.process(f, "svc");

            assertThat(chunks).isEmpty();
        }
    }
}
