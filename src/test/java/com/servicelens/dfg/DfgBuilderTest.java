package com.servicelens.dfg;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DfgBuilder}.
 *
 * <p>Each test parses an inline Java snippet with <em>JavaParser</em>, passes
 * the extracted {@link MethodDeclaration} to {@link DfgBuilder#build}, and
 * then asserts on the resulting {@link MethodDataFlow}.</p>
 *
 * <p>Test categories:</p>
 * <ul>
 *   <li>Parameter definitions — {@link DataFlowNode.DefType#PARAMETER}.</li>
 *   <li>Local variable declarations — {@link DataFlowNode.DefType#LOCAL_DECL}.</li>
 *   <li>Assignments — {@link DataFlowNode.DefType#ASSIGNMENT}.</li>
 *   <li>Loop variable definitions — {@link DataFlowNode.DefType#LOOP_VAR}.</li>
 *   <li>Condition and return-value USE sites.</li>
 *   <li>External reference detection via {@link MethodDataFlow#getExternalReferences()}.</li>
 *   <li>Summary map structure from {@link MethodDataFlow#toSummaryMap()}.</li>
 * </ul>
 *
 * <p>No Spring context or mocks are needed — {@code DfgBuilder} is a pure
 * stateless component.</p>
 */
@DisplayName("DfgBuilder")
class DfgBuilderTest {

    /** The component under test. */
    private DfgBuilder dfgBuilder;

    private static final String SERVICE   = "payment-service";
    private static final String METHOD_QN = "com.example.PaymentService.process";

    @BeforeEach
    void setUp() {
        dfgBuilder = new DfgBuilder();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Parameter definitions
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Parameter definitions")
    class ParameterDefinitionTests {

        @Test
        @DisplayName("Each formal parameter produces a PARAMETER DEF node")
        void eachParameterProducesDefNode() {
            MethodDeclaration method = parseMethod(
                    "void process(String orderId, int amount) {}");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(defsOfType(flow, DataFlowNode.DefType.PARAMETER))
                    .extracting(DataFlowNode::getVariableName)
                    .containsExactlyInAnyOrder("orderId", "amount");
        }

        @Test
        @DisplayName("Parameter DEF nodes carry the correct variable type")
        void parameterDefCarriesType() {
            MethodDeclaration method = parseMethod("void pay(Order order) {}");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            List<DataFlowNode> params = defsOfType(flow, DataFlowNode.DefType.PARAMETER);
            assertThat(params).hasSize(1);
            assertThat(params.get(0).getVariableType()).isEqualTo("Order");
        }

        @Test
        @DisplayName("Method without parameters produces zero PARAMETER DEF nodes")
        void noParametersProducesZeroDefs() {
            MethodDeclaration method = parseMethod("void noArgs() { int x = 1; }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(defsOfType(flow, DataFlowNode.DefType.PARAMETER)).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Local variable declarations
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Local variable declarations")
    class LocalDeclarationTests {

        @Test
        @DisplayName("Local declaration with initialiser creates a LOCAL_DECL DEF node")
        void localDeclProducesDefNode() {
            MethodDeclaration method = parseMethod(
                    "void run() { int result = compute(); }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(defsOfType(flow, DataFlowNode.DefType.LOCAL_DECL))
                    .extracting(DataFlowNode::getVariableName)
                    .contains("result");
        }

        @Test
        @DisplayName("LOCAL_DECL DEF node carries the source expression (initialiser)")
        void localDeclCarriesSourceExpression() {
            MethodDeclaration method = parseMethod(
                    "void run() { int total = a + b; }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            DataFlowNode def = defsOfType(flow, DataFlowNode.DefType.LOCAL_DECL)
                    .stream().filter(d -> "total".equals(d.getVariableName()))
                    .findFirst().orElseThrow();

            assertThat(def.getSourceExpression()).contains("a + b");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Assignment definitions
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Assignment definitions")
    class AssignmentDefinitionTests {

        @Test
        @DisplayName("Plain assignment to an existing variable produces an ASSIGNMENT DEF node")
        void assignmentProducesDefNode() {
            MethodDeclaration method = parseMethod(
                    "void update() { int x = 0; x = 42; }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(defsOfType(flow, DataFlowNode.DefType.ASSIGNMENT))
                    .extracting(DataFlowNode::getVariableName)
                    .contains("x");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Loop variable definitions
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("For-each loop variable definitions")
    class LoopVarTests {

        @Test
        @DisplayName("Enhanced for-each iteration variable produces a LOOP_VAR DEF node")
        void forEachVarProducesDefNode() {
            MethodDeclaration method = parseMethod(
                    "void each(java.util.List<String> items) { " +
                    "  for (String item : items) { process(item); } }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(defsOfType(flow, DataFlowNode.DefType.LOOP_VAR))
                    .extracting(DataFlowNode::getVariableName)
                    .contains("item");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // USE site tracking
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("USE site tracking")
    class UseTrackingTests {

        @Test
        @DisplayName("if-condition variable is recorded as a CONDITION USE")
        void ifConditionProducesConditionUse() {
            MethodDeclaration method = parseMethod(
                    "void check(int amount) { if (amount > 0) { charge(); } }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(usesOfType(flow, DataFlowUse.UseType.CONDITION))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("Returned variable is recorded as a RETURN_VALUE USE")
        void returnedVariableProducesReturnUse() {
            MethodDeclaration method = parseMethod(
                    "String getResult() { String result = compute(); return result; }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(usesOfType(flow, DataFlowUse.UseType.RETURN_VALUE))
                    .extracting(DataFlowUse::getVariableName)
                    .contains("result");
        }

        @Test
        @DisplayName("Method argument variables are recorded as METHOD_ARG USEs")
        void methodArgProducesMethodArgUse() {
            MethodDeclaration method = parseMethod(
                    "void send(String msg) { gateway.send(msg); }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(usesOfType(flow, DataFlowUse.UseType.METHOD_ARG))
                    .isNotEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // External references
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getExternalReferences()")
    class ExternalReferencesTests {

        @Test
        @DisplayName("Field-like references (not locally defined) appear as external references")
        void fieldReferencesAreExternal() {
            // 'paymentRepo' is used but never locally defined inside this method
            MethodDeclaration method = parseMethod(
                    "void save(Order order) { paymentRepo.save(order); }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);
            Set<String> external = flow.getExternalReferences();

            assertThat(external).contains("paymentRepo");
        }

        @Test
        @DisplayName("Locally defined variables are NOT in external references")
        void localVarsAreNotExternal() {
            MethodDeclaration method = parseMethod(
                    "void compute() { int x = 1; int y = x + 2; }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);
            Set<String> external = flow.getExternalReferences();

            assertThat(external).doesNotContain("x", "y");
        }

        @Test
        @DisplayName("Parameters are NOT in external references")
        void parametersAreNotExternal() {
            MethodDeclaration method = parseMethod(
                    "void process(Order order) { log(order); }");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);
            Set<String> external = flow.getExternalReferences();

            assertThat(external).doesNotContain("order");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Identity fields
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MethodDataFlow identity fields")
    class IdentityTests {

        @Test
        @DisplayName("Built MethodDataFlow carries the supplied method QN and service name")
        void identityFieldsArePreserved() {
            MethodDeclaration method = parseMethod("void run() {}");

            MethodDataFlow flow = dfgBuilder.build(method, METHOD_QN, SERVICE);

            assertThat(flow.getMethodQualifiedName()).isEqualTo(METHOD_QN);
            assertThat(flow.getServiceName()).isEqualTo(SERVICE);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Parse the first method declaration found in a minimal class wrapper.
     *
     * @param methodSource the method body, without an enclosing class
     * @return the first {@link MethodDeclaration} from the parsed AST
     */
    private static MethodDeclaration parseMethod(String methodSource) {
        String classSource = "class TestClass { " + methodSource + " }";
        CompilationUnit cu = StaticJavaParser.parse(classSource);
        return cu.findFirst(MethodDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No method found in: " + classSource));
    }

    /**
     * Filter the definition list of a {@link MethodDataFlow} by {@link DataFlowNode.DefType}.
     *
     * @param flow    the data-flow result to filter
     * @param defType the definition type to select
     * @return all matching definition nodes
     */
    private static List<DataFlowNode> defsOfType(MethodDataFlow flow,
                                                  DataFlowNode.DefType defType) {
        return flow.getDefinitions().stream()
                .filter(d -> d.getDefType() == defType)
                .toList();
    }

    /**
     * Filter the use list of a {@link MethodDataFlow} by {@link DataFlowUse.UseType}.
     *
     * @param flow    the data-flow result to filter
     * @param useType the use type to select
     * @return all matching use nodes
     */
    private static List<DataFlowUse> usesOfType(MethodDataFlow flow,
                                                 DataFlowUse.UseType useType) {
        return flow.getUses().stream()
                .filter(u -> u.getUseType() == useType)
                .toList();
    }
}
