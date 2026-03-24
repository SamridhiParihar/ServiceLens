package com.servicelens.graph;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphDeleter}.
 *
 * <p>Verifies that {@code deleteBatch} issues label-specific Cypher queries
 * (so Neo4j can use its {@code serviceName} indexes) and that the returned
 * count is the sum across all three labels: {@code CfgNode}, {@code Method},
 * and {@code Class}.</p>
 *
 * <p>{@link Neo4jClient} is mocked — no live Neo4j instance is required.</p>
 */
@DisplayName("GraphDeleter")
@ExtendWith(MockitoExtension.class)
class GraphDeleterTest {

    @Mock
    private Neo4jClient neo4jClient;

    private GraphDeleter graphDeleter;

    private static final String SERVICE = "payment-service";

    @BeforeEach
    void setUp() {
        graphDeleter = new GraphDeleter(neo4jClient);
    }

    // ─────────────────────────────────────────────────────────────────────
    // deleteBatch()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteBatch()")
    class DeleteBatchTests {

        @Test
        @DisplayName("Returns sum of deleted nodes across all three labels")
        void returnsSumAcrossLabels() {
            stubLabel("CfgNode", 10L);
            stubLabel("Method", 20L);
            stubLabel("Class",  5L);

            assertThat(graphDeleter.deleteBatch(SERVICE)).isEqualTo(35L);
        }

        @Test
        @DisplayName("Returns zero when all labels find no nodes")
        void returnsZeroWhenNothingToDelete() {
            stubLabel("CfgNode", 0L);
            stubLabel("Method",  0L);
            stubLabel("Class",   0L);

            assertThat(graphDeleter.deleteBatch(SERVICE)).isEqualTo(0L);
        }

        @Test
        @DisplayName("Queries CfgNode, Method, and Class labels separately")
        void issuesQueryForEachLabel() {
            stubLabel("CfgNode", 0L);
            stubLabel("Method",  0L);
            stubLabel("Class",   0L);

            graphDeleter.deleteBatch(SERVICE);

            verify(neo4jClient).query(contains(":CfgNode"));
            verify(neo4jClient).query(contains(":Method"));
            verify(neo4jClient).query(contains(":Class"));
        }

        @Test
        @DisplayName("Binds the service name as a Cypher parameter, not inline")
        void bindsServiceNameAsParameter() {
            Neo4jClient.UnboundRunnableSpec cfgSpec   = stubLabel("CfgNode", 0L);
            Neo4jClient.UnboundRunnableSpec methodSpec = stubLabel("Method",  0L);
            Neo4jClient.UnboundRunnableSpec classSpec  = stubLabel("Class",   0L);

            graphDeleter.deleteBatch(SERVICE);

            verify(cfgSpec).bindAll(argThat(m -> SERVICE.equals(m.get("serviceName"))));
            verify(methodSpec).bindAll(argThat(m -> SERVICE.equals(m.get("serviceName"))));
            verify(classSpec).bindAll(argThat(m -> SERVICE.equals(m.get("serviceName"))));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Stubs the full {@link Neo4jClient} fluent chain for a given label,
     * returning the supplied count. Returns the {@link Neo4jClient.UnboundRunnableSpec}
     * mock so callers can add further verifications.
     */
    @SuppressWarnings("unchecked")
    private Neo4jClient.UnboundRunnableSpec stubLabel(String label, long count) {
        Neo4jClient.UnboundRunnableSpec querySpec  = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.RunnableSpecBoundToDatabase boundSpec = mock(Neo4jClient.RunnableSpecBoundToDatabase.class);
        Neo4jClient.MappingSpec<Long> mappingSpec  = mock(Neo4jClient.MappingSpec.class);
        Neo4jClient.RecordFetchSpec<Long> fetchSpec = mock(Neo4jClient.RecordFetchSpec.class);

        when(neo4jClient.query(contains(":" + label))).thenReturn(querySpec);
        when(querySpec.bindAll(any(Map.class))).thenReturn(boundSpec);
        when(boundSpec.fetchAs(Long.class)).thenReturn(mappingSpec);
        when(mappingSpec.mappedBy(any())).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Optional.of(count));

        return querySpec;
    }
}
