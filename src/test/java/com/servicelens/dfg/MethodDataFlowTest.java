package com.servicelens.dfg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MethodDataFlow}.
 *
 * <p>Tests cover all public query methods:</p>
 * <ul>
 *   <li>{@link MethodDataFlow#getDefsFor(String)}</li>
 *   <li>{@link MethodDataFlow#getUsesFor(String)}</li>
 *   <li>{@link MethodDataFlow#getExternalReferences()}</li>
 *   <li>{@link MethodDataFlow#toSummaryMap()}</li>
 * </ul>
 *
 * <p>{@link DataFlowNode} and {@link DataFlowUse} instances are constructed
 * directly — no JavaParser or mocks needed.  This keeps the tests fast and
 * independent of the {@link DfgBuilder} parsing logic.</p>
 */
@DisplayName("MethodDataFlow")
class MethodDataFlowTest {

    private static final String METHOD_QN = "com.example.PaymentService.processPayment";
    private static final String SERVICE   = "payment-service";

    /** The object under test, populated fresh for each test. */
    private MethodDataFlow flow;

    @BeforeEach
    void setUp() {
        flow = new MethodDataFlow(METHOD_QN, SERVICE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Identity
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Identity accessors")
    class IdentityTests {

        @Test
        @DisplayName("getMethodQualifiedName() returns the value supplied at construction")
        void methodQnIsPreserved() {
            assertThat(flow.getMethodQualifiedName()).isEqualTo(METHOD_QN);
        }

        @Test
        @DisplayName("getServiceName() returns the value supplied at construction")
        void serviceNameIsPreserved() {
            assertThat(flow.getServiceName()).isEqualTo(SERVICE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getDefsFor()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getDefsFor()")
    class GetDefsForTests {

        @Test
        @DisplayName("Returns all DEF nodes whose variable name matches")
        void returnsMatchingDefs() {
            flow.addDef(def("order", DataFlowNode.DefType.PARAMETER));
            flow.addDef(def("order", DataFlowNode.DefType.ASSIGNMENT));  // re-assigned
            flow.addDef(def("amount", DataFlowNode.DefType.LOCAL_DECL));

            assertThat(flow.getDefsFor("order")).hasSize(2);
        }

        @Test
        @DisplayName("Returns empty list when no DEF matches the variable name")
        void returnsEmptyListWhenNoMatch() {
            flow.addDef(def("result", DataFlowNode.DefType.LOCAL_DECL));

            assertThat(flow.getDefsFor("unknownVar")).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getUsesFor()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getUsesFor()")
    class GetUsesForTests {

        @Test
        @DisplayName("Returns all USE nodes whose variable name matches")
        void returnsMatchingUses() {
            flow.addUse(use("amount", DataFlowUse.UseType.CONDITION));
            flow.addUse(use("amount", DataFlowUse.UseType.METHOD_ARG));
            flow.addUse(use("result", DataFlowUse.UseType.RETURN_VALUE));

            assertThat(flow.getUsesFor("amount")).hasSize(2);
        }

        @Test
        @DisplayName("Returns empty list when no USE matches the variable name")
        void returnsEmptyListWhenNoMatch() {
            flow.addUse(use("order", DataFlowUse.UseType.METHOD_ARG));

            assertThat(flow.getUsesFor("nonExistent")).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getExternalReferences()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getExternalReferences()")
    class ExternalReferencesTests {

        @Test
        @DisplayName("Variables used but never defined are external references")
        void usedButNotDefinedIsExternal() {
            // Only a USE for 'paymentRepo' — no DEF
            flow.addUse(use("paymentRepo", DataFlowUse.UseType.METHOD_ARG));

            Set<String> external = flow.getExternalReferences();

            assertThat(external).contains("paymentRepo");
        }

        @Test
        @DisplayName("Variables that are both defined and used are NOT external")
        void definedAndUsedIsNotExternal() {
            flow.addDef(def("amount", DataFlowNode.DefType.LOCAL_DECL));
            flow.addUse(use("amount", DataFlowUse.UseType.CONDITION));

            assertThat(flow.getExternalReferences()).doesNotContain("amount");
        }

        @Test
        @DisplayName("Parameter variables are NOT external references")
        void parametersAreNotExternal() {
            flow.addDef(def("order", DataFlowNode.DefType.PARAMETER));
            flow.addUse(use("order", DataFlowUse.UseType.METHOD_ARG));

            assertThat(flow.getExternalReferences()).doesNotContain("order");
        }

        @Test
        @DisplayName("'this' is always excluded from external references")
        void thisIsExcluded() {
            flow.addUse(use("this", DataFlowUse.UseType.METHOD_ARG));

            assertThat(flow.getExternalReferences()).doesNotContain("this");
        }

        @Test
        @DisplayName("Returns empty set when no external references exist")
        void emptyWhenNoExternalRefs() {
            flow.addDef(def("x", DataFlowNode.DefType.LOCAL_DECL));
            flow.addUse(use("x", DataFlowUse.UseType.RETURN_VALUE));

            assertThat(flow.getExternalReferences()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // toSummaryMap()
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toSummaryMap()")
    class ToSummaryMapTests {

        @Test
        @DisplayName("Returns empty map when there are no definitions or uses")
        void emptyMapForEmptyFlow() {
            assertThat(flow.toSummaryMap()).isEmpty();
        }

        @Test
        @DisplayName("Each variable with DEFs appears as a top-level key")
        void variableWithDefAppearsAsKey() {
            flow.addDef(def("order", DataFlowNode.DefType.PARAMETER));

            Map<String, Object> summary = flow.toSummaryMap();

            assertThat(summary).containsKey("order");
        }

        @Test
        @DisplayName("Each variable entry contains a 'defs' list")
        void variableEntryContainsDefs() {
            flow.addDef(def("amount", DataFlowNode.DefType.LOCAL_DECL));

            Map<String, Object> summary = flow.toSummaryMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> amountEntry = (Map<String, Object>) summary.get("amount");

            assertThat(amountEntry).containsKey("defs");
            assertThat((List<?>) amountEntry.get("defs")).hasSize(1);
        }

        @Test
        @DisplayName("Each variable entry contains a 'uses' list when uses exist")
        void variableEntryContainsUses() {
            flow.addDef(def("result", DataFlowNode.DefType.LOCAL_DECL));
            flow.addUse(use("result", DataFlowUse.UseType.RETURN_VALUE));

            Map<String, Object> summary = flow.toSummaryMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> resultEntry = (Map<String, Object>) summary.get("result");

            assertThat(resultEntry).containsKey("uses");
            assertThat((List<?>) resultEntry.get("uses")).hasSize(1);
        }

        @Test
        @DisplayName("DEF entry map contains 'type' and 'line' keys")
        void defEntryHasTypeAndLine() {
            flow.addDef(new DataFlowNode("total", "int",
                    DataFlowNode.DefType.LOCAL_DECL, "a + b", 5));

            Map<String, Object> summary = flow.toSummaryMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> totalEntry = (Map<String, Object>) summary.get("total");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> defs = (List<Map<String, Object>>) totalEntry.get("defs");

            assertThat(defs.get(0))
                    .containsKey("type")
                    .containsKey("line");
        }

        @Test
        @DisplayName("DEF entry 'type' value matches the DefType name")
        void defEntryTypeMatchesEnumName() {
            flow.addDef(new DataFlowNode("x", "int",
                    DataFlowNode.DefType.ASSIGNMENT, "42", 10));

            Map<String, Object> summary = flow.toSummaryMap();
            @SuppressWarnings("unchecked")
            Map<String, Object> xEntry = (Map<String, Object>) summary.get("x");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> defs = (List<Map<String, Object>>) xEntry.get("defs");

            assertThat(defs.get(0).get("type")).isEqualTo("ASSIGNMENT");
        }

        @Test
        @DisplayName("Variables with only USEs (external references) still appear in summary")
        void externalRefAppearsInSummary() {
            flow.addUse(use("paymentRepo", DataFlowUse.UseType.METHOD_ARG));

            Map<String, Object> summary = flow.toSummaryMap();

            assertThat(summary).containsKey("paymentRepo");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Build a minimal {@link DataFlowNode} for a given variable name and def type.
     *
     * @param varName the variable name
     * @param defType the kind of definition
     * @return a {@code DataFlowNode} with line 1 and no source expression
     */
    private static DataFlowNode def(String varName, DataFlowNode.DefType defType) {
        return new DataFlowNode(varName, null, defType, null, 1);
    }

    /**
     * Build a minimal {@link DataFlowUse} for a given variable name and use type.
     *
     * @param varName the variable name
     * @param useType the kind of use
     * @return a {@code DataFlowUse} with line 1 and a simple context string
     */
    private static DataFlowUse use(String varName, DataFlowUse.UseType useType) {
        return new DataFlowUse(varName, useType, varName + ".expr", 1);
    }
}
