package com.servicelens.cfg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CfgBuilder}.
 *
 * <p>Each test parses an inline Java source snippet with <em>JavaParser</em>,
 * extracts the first method declaration, and feeds it to {@link CfgBuilder#build}.
 * This approach exercises real Java AST traversal without any file I/O or mocks.</p>
 *
 * <p>Structural invariants verified per method:</p>
 * <ul>
 *   <li>The first node is always {@link CfgNode.CfgNodeType#ENTRY}.</li>
 *   <li>The last node is always {@link CfgNode.CfgNodeType#EXIT}.</li>
 *   <li>Complex control structures (if, while, for, try-catch) produce the
 *       expected CFG node types.</li>
 *   <li>A single-statement method yields a minimal node list.</li>
 *   <li>Cyclomatic complexity can be approximated from the node list size
 *       (tests validate count ranges rather than exact values to be robust
 *       against minor structural changes).</li>
 * </ul>
 *
 * <p>No Spring context is required.</p>
 */
@DisplayName("CfgBuilder")
class CfgBuilderTest {

    /** The component under test. */
    private CfgBuilder cfgBuilder;

    private static final String SERVICE  = "payment-service";
    private static final String METHOD_QN = "com.example.PaymentService.processPayment";

    @BeforeEach
    void setUp() {
        cfgBuilder = new CfgBuilder();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTRY / EXIT invariants
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ENTRY and EXIT invariants")
    class EntryExitTests {

        @Test
        @DisplayName("First node is ENTRY, last node is EXIT for any method")
        void firstNodeEntryLastNodeExit() {
            MethodDeclaration method = parseMethod("void simple() { int x = 1; }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(cfg).isNotEmpty();
            assertThat(cfg.get(0).getNodeType()).isEqualTo(CfgNode.CfgNodeType.ENTRY);
            assertThat(cfg.get(cfg.size() - 1).getNodeType()).isEqualTo(CfgNode.CfgNodeType.EXIT);
        }

        @Test
        @DisplayName("Empty method body produces exactly ENTRY → EXIT")
        void emptyMethodBody() {
            MethodDeclaration method = parseMethod("void empty() {}");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(cfg).hasSize(2);
            assertThat(cfg.get(0).getNodeType()).isEqualTo(CfgNode.CfgNodeType.ENTRY);
            assertThat(cfg.get(1).getNodeType()).isEqualTo(CfgNode.CfgNodeType.EXIT);
        }

        @Test
        @DisplayName("ENTRY node records the method name in its code field")
        void entryNodeContainsMethodName() {
            MethodDeclaration method = parseMethod("void processPayment() {}");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(cfg.get(0).getCode()).contains("processPayment");
        }

        @Test
        @DisplayName("All nodes carry the expected methodQualifiedName and serviceName")
        void nodesCarryMethodAndServiceName() {
            MethodDeclaration method = parseMethod("void tagged() { int a = 1; }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            cfg.forEach(node -> {
                assertThat(node.getMethodQualifiedName()).isEqualTo(METHOD_QN);
                assertThat(node.getServiceName()).isEqualTo(SERVICE);
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // if-statement
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("if-statement")
    class IfStatementTests {

        @Test
        @DisplayName("Simple if produces a CONDITION node")
        void simpleIfProducesConditionNode() {
            MethodDeclaration method = parseMethod(
                    "void check(int x) { if (x > 0) { doA(); } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(containsType(cfg, CfgNode.CfgNodeType.CONDITION)).isTrue();
        }

        @Test
        @DisplayName("if-else produces more than 3 nodes (ENTRY, condition, branches, merge, EXIT)")
        void ifElseProducesMultipleNodes() {
            MethodDeclaration method = parseMethod(
                    "void decide(boolean flag) { if (flag) { doA(); } else { doB(); } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(cfg.size()).isGreaterThan(3);
        }

        @Test
        @DisplayName("CONDITION node's code field includes the condition expression text")
        void conditionNodeCodeContainsExpression() {
            MethodDeclaration method = parseMethod(
                    "void guard(int amount) { if (amount > 100) { charge(); } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            CfgNode condNode = findFirst(cfg, CfgNode.CfgNodeType.CONDITION);
            assertThat(condNode.getCode()).contains("amount");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // while loop
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("while-loop")
    class WhileLoopTests {

        @Test
        @DisplayName("While loop produces a LOOP_HEADER node")
        void whileProducesLoopHeader() {
            MethodDeclaration method = parseMethod(
                    "void loop(int n) { while (n > 0) { n--; } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(containsType(cfg, CfgNode.CfgNodeType.LOOP_HEADER)).isTrue();
        }

        @Test
        @DisplayName("LOOP_HEADER node carries the loop condition")
        void loopHeaderCarriesCondition() {
            MethodDeclaration method = parseMethod(
                    "void retry(int max) { while (max > 0) { max--; } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            CfgNode header = findFirst(cfg, CfgNode.CfgNodeType.LOOP_HEADER);
            assertThat(header.getCondition()).contains("max");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // for / for-each loops
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("for / for-each loops")
    class ForLoopTests {

        @Test
        @DisplayName("Standard for-loop produces a LOOP_HEADER node")
        void forLoopProducesLoopHeader() {
            MethodDeclaration method = parseMethod(
                    "void iterate(int n) { for (int i = 0; i < n; i++) { process(i); } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(containsType(cfg, CfgNode.CfgNodeType.LOOP_HEADER)).isTrue();
        }

        @Test
        @DisplayName("Enhanced for-each loop produces a LOOP_HEADER node")
        void forEachProducesLoopHeader() {
            MethodDeclaration method = parseMethod(
                    "void process(java.util.List<String> items) { " +
                    "  for (String item : items) { handle(item); } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(containsType(cfg, CfgNode.CfgNodeType.LOOP_HEADER)).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // try-catch
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("try-catch")
    class TryCatchTests {

        @Test
        @DisplayName("Try-catch block produces an EXCEPTION_HANDLER node")
        void tryCatchProducesExceptionHandler() {
            MethodDeclaration method = parseMethod(
                    "void safeCall() { " +
                    "  try { riskyOp(); } " +
                    "  catch (Exception e) { logError(e); } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(containsType(cfg, CfgNode.CfgNodeType.EXCEPTION_HANDLER)).isTrue();
        }

        @Test
        @DisplayName("EXCEPTION_HANDLER node carries the exception type")
        void exceptionHandlerCarriesType() {
            MethodDeclaration method = parseMethod(
                    "void guarded() { " +
                    "  try { pay(); } " +
                    "  catch (IllegalStateException ex) { recover(); } }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            CfgNode handler = findFirst(cfg, CfgNode.CfgNodeType.EXCEPTION_HANDLER);
            assertThat(handler.getExceptionType()).isEqualTo("IllegalStateException");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // method call nodes
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("METHOD_CALL nodes")
    class MethodCallTests {

        @Test
        @DisplayName("A method call statement creates a METHOD_CALL node")
        void methodCallStatementCreatesNode() {
            MethodDeclaration method = parseMethod(
                    "void run() { service.execute(); }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(containsType(cfg, CfgNode.CfgNodeType.METHOD_CALL)).isTrue();
        }

        @Test
        @DisplayName("METHOD_CALL node records the called method name")
        void methodCallNodeRecordsMethodName() {
            MethodDeclaration method = parseMethod(
                    "void trigger() { paymentGateway.charge(100); }");

            List<CfgNode> cfg = cfgBuilder.build(method, METHOD_QN, SERVICE);

            CfgNode callNode = findFirst(cfg, CfgNode.CfgNodeType.METHOD_CALL);
            assertThat(callNode.getCalledMethod()).isEqualTo("charge");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Parse a single method from a minimal class wrapper and return the first
     * {@link MethodDeclaration} found.
     *
     * @param methodSource the method body source code (without enclosing class)
     * @return the parsed method declaration
     */
    private static MethodDeclaration parseMethod(String methodSource) {
        String classSource = "class TestClass { " + methodSource + " }";
        CompilationUnit cu = StaticJavaParser.parse(classSource);
        return cu.findFirst(MethodDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No method found in: " + classSource));
    }

    /** Return {@code true} if any node in the list has the given type. */
    private static boolean containsType(List<CfgNode> nodes, CfgNode.CfgNodeType type) {
        return nodes.stream().anyMatch(n -> n.getNodeType() == type);
    }

    /**
     * Find the first node of the given type or fail with a descriptive message.
     *
     * @param nodes the list to search
     * @param type  the desired node type
     * @return the first matching node
     */
    private static CfgNode findFirst(List<CfgNode> nodes, CfgNode.CfgNodeType type) {
        return nodes.stream()
                .filter(n -> n.getNodeType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No CFG node of type " + type + " found in list of " + nodes.size()));
    }
}
