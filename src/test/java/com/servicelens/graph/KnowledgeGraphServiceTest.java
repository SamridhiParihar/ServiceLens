package com.servicelens.graph;

import com.servicelens.cfg.CfgNode;
import com.servicelens.chunking.processors.JavaFileProcessor;
import com.servicelens.dfg.MethodDataFlow;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.domain.NodeType;
import com.servicelens.graph.repository.CfgNodeRepository;
import com.servicelens.graph.repository.ClassNodeRepository;
import com.servicelens.graph.repository.MethodNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KnowledgeGraphService}.
 *
 * <p>{@code KnowledgeGraphService} is a facade over three Spring Data Neo4j
 * repositories, a {@link CfgSaver}, and a {@link GraphDeleter}.  All collaborators
 * are replaced by Mockito mocks so that tests run without a live Neo4j instance.</p>
 *
 * <p>Test categories:</p>
 * <ul>
 *   <li><strong>saveFileResult</strong> — verifies that class nodes, method nodes, and
 *       CFG nodes from a {@link JavaFileProcessor.JavaFileResult} are each persisted
 *       to the correct repository.</li>
 *   <li><strong>deleteServiceGraph</strong> — verifies that {@link GraphDeleter#deleteBatch}
 *       is called in a loop until it returns zero.</li>
 *   <li><strong>Read delegation</strong> — verifies that every read method on the service
 *       delegates to the appropriate repository method with the same arguments.</li>
 *   <li><strong>getServiceStats</strong> — verifies that the stats map contains the
 *       expected keys and is computed from repository data.</li>
 * </ul>
 *
 * <p>No Spring context is required.</p>
 */
@DisplayName("KnowledgeGraphService")
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphServiceTest {

    @Mock
    private ClassNodeRepository classRepo;

    @Mock
    private MethodNodeRepository methodRepo;

    @Mock
    private CfgNodeRepository cfgRepo;

    @Mock
    private CfgSaver cfgSaver;

    @Mock
    private GraphDeleter graphDeleter;

    /** The component under test. */
    private KnowledgeGraphService graphService;

    private static final String SERVICE = "payment-service";

    @BeforeEach
    void setUp() {
        graphService = new KnowledgeGraphService(classRepo, methodRepo, cfgRepo, cfgSaver, graphDeleter);
    }

    // ─────────────────────────────────────────────────────────────────────
    // saveFileResult()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("saveFileResult()")
    class SaveFileResultTests {

        @Test
        @DisplayName("Saves each class node to ClassNodeRepository")
        void savesEachClassNode() {
            ClassNode cls = classNode("PaymentService");
            JavaFileProcessor.JavaFileResult result = fileResult(
                    List.of(cls), List.of(), List.of());

            graphService.saveFileResult(result);

            verify(classRepo).save(cls);
        }

        @Test
        @DisplayName("Saves each method node to MethodNodeRepository")
        void savesEachMethodNode() {
            MethodNode method = methodNode("processPayment");
            JavaFileProcessor.JavaFileResult result = fileResult(
                    List.of(), List.of(method), List.of());

            graphService.saveFileResult(result);

            verify(methodRepo).save(method);
        }

        @Test
        @DisplayName("Saves all CFG nodes in bulk to CfgNodeRepository")
        void savesCfgNodesInBulk() {
            CfgNode node1 = cfgNode();
            CfgNode node2 = cfgNode();
            JavaFileProcessor.JavaFileResult result = fileResult(
                    List.of(), List.of(), List.of(node1, node2));

            graphService.saveFileResult(result);

            verify(cfgSaver).save(List.of(node1, node2));
        }

        @Test
        @DisplayName("Skips CfgNodeRepository call when CFG node list is empty")
        void skipsCfgSaveWhenEmpty() {
            JavaFileProcessor.JavaFileResult result = fileResult(
                    List.of(classNode("Foo")), List.of(), List.of());

            graphService.saveFileResult(result);

            verify(cfgSaver, never()).save(any());
        }

        @Test
        @DisplayName("Continues saving remaining nodes even when one class node save fails")
        void continuesOnClassSaveFailure() {
            ClassNode bad  = classNode("Broken");
            ClassNode good = classNode("WorkingService");
            doThrow(new RuntimeException("Neo4j error")).when(classRepo).save(bad);

            JavaFileProcessor.JavaFileResult result = fileResult(
                    List.of(bad, good), List.of(), List.of());

            graphService.saveFileResult(result);

            // Verify the good node was still attempted
            verify(classRepo).save(good);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // deleteServiceGraph()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteServiceGraph()")
    class DeleteServiceGraphTests {

        @Test
        @DisplayName("Calls deleteBatch until it returns zero")
        void callsDeleteBatchUntilExhausted() {
            // first call deletes 3 nodes, second call finds nothing left
            when(graphDeleter.deleteBatch(SERVICE)).thenReturn(3L, 0L);

            graphService.deleteServiceGraph(SERVICE);

            verify(graphDeleter, times(2)).deleteBatch(SERVICE);
        }

        @Test
        @DisplayName("Stops after single batch when graph is already empty")
        void stopsImmediatelyWhenAlreadyEmpty() {
            when(graphDeleter.deleteBatch(SERVICE)).thenReturn(0L);

            graphService.deleteServiceGraph(SERVICE);

            verify(graphDeleter, times(1)).deleteBatch(SERVICE);
        }

        @Test
        @DisplayName("Loops for multiple batches until fully deleted")
        void loopsForMultipleBatches() {
            when(graphDeleter.deleteBatch(SERVICE)).thenReturn(500L, 500L, 200L, 0L);

            graphService.deleteServiceGraph(SERVICE);

            verify(graphDeleter, times(4)).deleteBatch(SERVICE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Read delegation: findCallers / findCallChain
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findCallers() and findCallChain() delegation")
    class CallTraversalDelegationTests {

        @Test
        @DisplayName("findCallers() delegates to MethodNodeRepository.findCallers()")
        void findCallersDelegatesToRepo() {
            MethodNode caller = methodNode("checkout");
            when(methodRepo.findCallers("com.example.PaymentService.processPayment"))
                    .thenReturn(List.of(caller));

            List<MethodNode> result = graphService.findCallers(
                    "com.example.PaymentService.processPayment");

            assertThat(result).containsExactly(caller);
        }

        @Test
        @DisplayName("findCallChain() delegates to MethodNodeRepository.findCallChain()")
        void findCallChainDelegatesToRepo() {
            MethodNode chainMethod = methodNode("validate");
            when(methodRepo.findCallChain("com.example.PaymentService.processPayment", SERVICE))
                    .thenReturn(List.of(chainMethod));

            List<MethodNode> result = graphService.findCallChain(
                    "com.example.PaymentService.processPayment", SERVICE);

            assertThat(result).containsExactly(chainMethod);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Read delegation: class hierarchy and impact
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("findSubclasses(), findImplementors(), findDependents() delegation")
    class ClassHierarchyDelegationTests {

        @Test
        @DisplayName("findSubclasses() delegates to ClassNodeRepository.findSubclasses()")
        void findSubclassesDelegatesToRepo() {
            ClassNode sub = classNode("ConcreteService");
            when(classRepo.findSubclasses("com.example.BaseService")).thenReturn(List.of(sub));

            List<ClassNode> result = graphService.findSubclasses("com.example.BaseService");

            assertThat(result).containsExactly(sub);
        }

        @Test
        @DisplayName("findImplementors() delegates to ClassNodeRepository.findImplementors()")
        void findImplementorsDelegatesToRepo() {
            ClassNode impl = classNode("PaymentServiceImpl");
            when(classRepo.findImplementors("com.example.Payable")).thenReturn(List.of(impl));

            List<ClassNode> result = graphService.findImplementors("com.example.Payable");

            assertThat(result).containsExactly(impl);
        }

        @Test
        @DisplayName("findDependents() delegates to ClassNodeRepository.findDependents()")
        void findDependentsDelegatesToRepo() {
            ClassNode dep = classNode("OrderService");
            when(classRepo.findDependents("com.example.PaymentService", SERVICE))
                    .thenReturn(List.of(dep));

            List<ClassNode> result = graphService.findDependents(
                    "com.example.PaymentService", SERVICE);

            assertThat(result).containsExactly(dep);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // findEndpoints / findTransactionalMethods / findHighComplexityMethods
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Method query delegation")
    class MethodQueryDelegationTests {

        @Test
        @DisplayName("findEndpoints() delegates to MethodNodeRepository.findEndpoints()")
        void findEndpointsDelegatesToRepo() {
            MethodNode endpoint = methodNode("getOrders");
            when(methodRepo.findEndpoints(SERVICE)).thenReturn(List.of(endpoint));

            List<MethodNode> result = graphService.findEndpoints(SERVICE);

            assertThat(result).containsExactly(endpoint);
        }

        @Test
        @DisplayName("findTransactionalMethods() delegates to MethodNodeRepository")
        void findTransactionalDelegatesToRepo() {
            MethodNode txMethod = methodNode("saveOrder");
            when(methodRepo.findTransactionalMethods(SERVICE)).thenReturn(List.of(txMethod));

            List<MethodNode> result = graphService.findTransactionalMethods(SERVICE);

            assertThat(result).containsExactly(txMethod);
        }

        @Test
        @DisplayName("findHighComplexityMethods() delegates with service name and threshold")
        void findHighComplexityDelegatesToRepo() {
            MethodNode complex = methodNode("processOrder");
            when(methodRepo.findHighComplexityMethods(SERVICE, 10))
                    .thenReturn(List.of(complex));

            List<MethodNode> result = graphService.findHighComplexityMethods(SERVICE, 10);

            assertThat(result).containsExactly(complex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getCfgForMethod()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getCfgForMethod() delegation")
    class GetCfgForMethodTests {

        @Test
        @DisplayName("getCfgForMethod() delegates to CfgNodeRepository.findByMethodQualifiedName()")
        void getCfgDelegatesToRepo() {
            CfgNode entryNode = cfgNode();
            when(cfgRepo.findByMethodQualifiedName("com.example.PaymentService.process"))
                    .thenReturn(List.of(entryNode));

            List<CfgNode> result = graphService.getCfgForMethod(
                    "com.example.PaymentService.process");

            assertThat(result).containsExactly(entryNode);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getServiceStats()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getServiceStats()")
    class GetServiceStatsTests {

        @Test
        @DisplayName("Stats map contains 'classes', 'methods', 'endpoints', 'highComplexityMethods'")
        void statsMapContainsExpectedKeys() {
            when(classRepo.findByServiceName(SERVICE)).thenReturn(List.of(classNode("A")));
            when(methodRepo.findByServiceName(SERVICE)).thenReturn(List.of(methodNode("m1"), methodNode("m2")));
            when(methodRepo.findEndpoints(SERVICE)).thenReturn(List.of(methodNode("ep")));
            when(methodRepo.findHighComplexityMethods(SERVICE, 10)).thenReturn(List.of());

            var stats = graphService.getServiceStats(SERVICE);

            assertThat(stats).containsKeys("classes", "methods", "endpoints", "highComplexityMethods");
        }

        @Test
        @DisplayName("Stats values reflect actual repository data counts")
        void statsValuesReflectRepositoryData() {
            when(classRepo.findByServiceName(SERVICE)).thenReturn(List.of(classNode("A"), classNode("B")));
            when(methodRepo.findByServiceName(SERVICE)).thenReturn(List.of(methodNode("m1")));
            when(methodRepo.findEndpoints(SERVICE)).thenReturn(List.of());
            when(methodRepo.findHighComplexityMethods(SERVICE, 10)).thenReturn(List.of(methodNode("complex")));

            var stats = graphService.getServiceStats(SERVICE);

            assertThat(stats.get("classes")).isEqualTo(2L);
            assertThat(stats.get("methods")).isEqualTo(1L);
            assertThat(stats.get("endpoints")).isEqualTo(0L);
            assertThat(stats.get("highComplexityMethods")).isEqualTo(1L);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Build a minimal {@link ClassNode} with its simple and qualified names set.
     *
     * @param simpleName the short class name
     * @return a populated {@link ClassNode}
     */
    private static ClassNode classNode(String simpleName) {
        ClassNode node = new ClassNode();
        node.setSimpleName(simpleName);
        node.setQualifiedName("com.example." + simpleName);
        node.setNodeType(NodeType.CLASS);
        return node;
    }

    /**
     * Build a minimal {@link MethodNode} with its simple and qualified names set.
     *
     * @param simpleName the short method name
     * @return a populated {@link MethodNode}
     */
    private static MethodNode methodNode(String simpleName) {
        MethodNode node = new MethodNode();
        node.setSimpleName(simpleName);
        node.setQualifiedName("com.example.SomeClass." + simpleName);
        return node;
    }

    /**
     * Build a minimal {@link CfgNode} with ENTRY type set.
     *
     * @return a populated {@link CfgNode}
     */
    private static CfgNode cfgNode() {
        CfgNode node = new CfgNode();
        node.setNodeType(CfgNode.CfgNodeType.ENTRY);
        node.setMethodQualifiedName("com.example.SomeClass.someMethod");
        return node;
    }

    /**
     * Build a {@link JavaFileProcessor.JavaFileResult} record with the supplied lists.
     *
     * <p>The record constructor signature is:
     * {@code JavaFileResult(chunks, documentationChunks, classNodes, methodNodes, cfgNodes, dataFlows)}.
     * The {@code chunks} and {@code documentationChunks} lists are left empty as they are
     * not persisted by {@link KnowledgeGraphService}.</p>
     *
     * @param classNodes  class nodes to include
     * @param methodNodes method nodes to include
     * @param cfgNodes    CFG nodes to include
     * @return a populated result record
     */
    private static JavaFileProcessor.JavaFileResult fileResult(
            List<ClassNode> classNodes,
            List<MethodNode> methodNodes,
            List<CfgNode> cfgNodes) {
        // Signature: chunks, documentationChunks, classNodes, methodNodes, cfgNodes, dataFlows
        return new JavaFileProcessor.JavaFileResult(
                List.of(),         // chunks (not relevant for graph service)
                List.of(),         // documentationChunks (not relevant for graph service)
                classNodes,
                methodNodes,
                cfgNodes,
                List.<MethodDataFlow>of()  // dataFlows (not used by graph service)
        );
    }
}
