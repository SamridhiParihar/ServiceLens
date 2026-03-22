package com.servicelens.functional.graph;

import com.servicelens.chunking.processors.JavaFileProcessor;
import com.servicelens.functional.support.FunctionalTestBase;
import com.servicelens.graph.KnowledgeGraphService;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodCallRelationship;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.domain.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for {@link KnowledgeGraphService}.
 *
 * <p>Tests drive the public service API against the live Neo4j container and
 * verify graph reads, traversals, deletions, and statistics.  All data is
 * wiped between tests by {@link FunctionalTestBase#cleanData()}.</p>
 */
@DisplayName("KnowledgeGraph — functional")
class KnowledgeGraphFunctionalTest extends FunctionalTestBase {

    private static final String SERVICE = "graph-test-svc";

    @Autowired
    private KnowledgeGraphService graphService;

    // ── Save and basic query ──────────────────────────────────────────────────

    @Test
    @DisplayName("Saving a ClassNode allows it to be found by serviceName via Cypher")
    void saveClassNode_isQueryableByServiceName() {
        save(classNode("com.example.OrderService", "OrderService"));

        long count;
        try (Session s = neo4jDriver.session()) {
            count = s.run("MATCH (c:Class {serviceName: $svc}) RETURN count(c) AS n",
                          Map.of("svc", SERVICE))
                     .single().get("n").asLong();
        }

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Saving multiple ClassNodes counts correctly in getServiceStats")
    void getServiceStats_classAndMethodCountsCorrect() {
        ClassNode order   = classNode("com.example.OrderService",   "OrderService");
        ClassNode payment = classNode("com.example.PaymentService", "PaymentService");

        MethodNode create = methodNode("createOrder", "com.example.OrderService.createOrder", order);
        MethodNode pay    = methodNode("pay",          "com.example.PaymentService.pay",        payment);

        save(order, payment, create, pay);

        Map<String, Object> stats = graphService.getServiceStats(SERVICE);

        assertThat(((Number) stats.get("classes")).longValue()).isEqualTo(2);
        assertThat(((Number) stats.get("methods")).longValue()).isEqualTo(2);
    }

    // ── Call chain ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findCallChain returns downstream methods reached from the entry point")
    void findCallChain_returnsDownstreamMethods() {
        ClassNode orderCls   = classNode("com.example.OrderService",   "OrderService");
        ClassNode paymentCls = classNode("com.example.PaymentService", "PaymentService");

        MethodNode createOrder = methodNode("createOrder",  "com.example.OrderService.createOrder",    orderCls);
        MethodNode pay         = methodNode("processPayment","com.example.PaymentService.processPayment", paymentCls);

        // createOrder → processPayment
        MethodCallRelationship callRel = new MethodCallRelationship();
        callRel.setCallee(pay);
        callRel.setCallLine(12);
        callRel.setCallType("DIRECT");
        createOrder.setCalls(List.of(callRel));

        save(orderCls, paymentCls, createOrder, pay);

        List<MethodNode> chain = graphService.findCallChain(
                "com.example.OrderService.createOrder", SERVICE);

        assertThat(chain).isNotEmpty();
        assertThat(chain).anyMatch(m -> m.getSimpleName().equals("processPayment"));
    }

    @Test
    @DisplayName("findCallChain returns empty list when entry point method does not exist")
    void findCallChain_emptyForUnknownEntryPoint() {
        List<MethodNode> chain = graphService.findCallChain(
                "com.example.NonExistent.method", SERVICE);

        assertThat(chain).isEmpty();
    }

    // ── Callers ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findCallers returns all methods that call the target method")
    void findCallers_returnsCaller() {
        ClassNode checkoutCls = classNode("com.example.CheckoutService", "CheckoutService");
        ClassNode orderCls    = classNode("com.example.OrderService",    "OrderService");

        MethodNode checkout    = methodNode("checkout",    "com.example.CheckoutService.checkout",    checkoutCls);
        MethodNode createOrder = methodNode("createOrder", "com.example.OrderService.createOrder",    orderCls);

        // checkout → createOrder
        MethodCallRelationship callRel = new MethodCallRelationship();
        callRel.setCallee(createOrder);
        callRel.setCallLine(25);
        callRel.setCallType("DIRECT");
        checkout.setCalls(List.of(callRel));

        save(checkoutCls, orderCls, checkout, createOrder);

        List<MethodNode> callers = graphService.findCallers(
                "com.example.OrderService.createOrder");

        assertThat(callers).anyMatch(m -> m.getSimpleName().equals("checkout"));
    }

    // ── Impact analysis ───────────────────────────────────────────────────────

    @Test
    @DisplayName("findDependents returns classes that depend on the target class")
    void findDependents_returnsDependent() {
        ClassNode paymentCls = classNode("com.example.PaymentService", "PaymentService");
        ClassNode orderCls   = classNode("com.example.OrderService",   "OrderService");

        // orderCls depends on paymentCls
        orderCls.setDependencies(List.of(paymentCls));

        save(paymentCls, orderCls);

        List<ClassNode> dependents = graphService.findDependents("PaymentService", SERVICE);

        assertThat(dependents).anyMatch(c -> c.getSimpleName().equals("OrderService"));
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findEndpoints returns only methods annotated as HTTP endpoints")
    void findEndpoints_returnsAnnotatedMethods() {
        ClassNode ctrl = classNode("com.example.OrderController", "OrderController");

        MethodNode createEndpoint = methodNode("createOrder", "com.example.OrderController.createOrder", ctrl);
        createEndpoint.setEndpoint(true);
        createEndpoint.setHttpMethod("POST");
        createEndpoint.setEndpointPath("/orders");

        MethodNode helper = methodNode("validateInput", "com.example.OrderController.validateInput", ctrl);
        helper.setEndpoint(false);

        save(ctrl, createEndpoint, helper);

        List<MethodNode> endpoints = graphService.findEndpoints(SERVICE);

        assertThat(endpoints).anyMatch(m -> m.getSimpleName().equals("createOrder"));
        assertThat(endpoints).noneMatch(m -> m.getSimpleName().equals("validateInput"));
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteServiceGraph removes all nodes for the service")
    void deleteServiceGraph_removesAllNodes() {
        save(classNode("com.example.OrderService", "OrderService"),
             classNode("com.example.PaymentService", "PaymentService"));

        graphService.deleteServiceGraph(SERVICE);

        long remaining;
        try (Session s = neo4jDriver.session()) {
            remaining = s.run("MATCH (n {serviceName: $svc}) RETURN count(n) AS n",
                              Map.of("svc", SERVICE))
                         .single().get("n").asLong();
        }

        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("deleteByFilePath removes only nodes associated with the given file")
    void deleteByFilePath_removesOnlyMatchingFileNodes() {
        ClassNode keep   = classNode("com.example.KeepService",   "KeepService");
        keep.setFilePath("/repo/KeepService.java");

        ClassNode remove = classNode("com.example.RemoveService", "RemoveService");
        remove.setFilePath("/repo/RemoveService.java");

        save(keep, remove);

        graphService.deleteByFilePath("/repo/RemoveService.java");

        long remaining;
        try (Session s = neo4jDriver.session()) {
            remaining = s.run("MATCH (c:Class {serviceName: $svc}) RETURN count(c) AS n",
                              Map.of("svc", SERVICE))
                         .single().get("n").asLong();
        }

        assertThat(remaining).isEqualTo(1);
    }

    // ── Transactional methods ─────────────────────────────────────────────────

    @Test
    @DisplayName("findTransactionalMethods returns only @Transactional-annotated methods")
    void findTransactionalMethods_onlyAnnotated() {
        ClassNode orderCls = classNode("com.example.OrderService", "OrderService");

        MethodNode txMethod  = methodNode("placeOrder", "com.example.OrderService.placeOrder", orderCls);
        txMethod.setTransactional(true);

        MethodNode readMethod = methodNode("getOrder", "com.example.OrderService.getOrder", orderCls);
        readMethod.setTransactional(false);

        save(orderCls, txMethod, readMethod);

        List<MethodNode> txMethods = graphService.findTransactionalMethods(SERVICE);

        assertThat(txMethods).anyMatch(m -> m.getSimpleName().equals("placeOrder"));
        assertThat(txMethods).noneMatch(m -> m.getSimpleName().equals("getOrder"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClassNode classNode(String qualifiedName, String simpleName) {
        ClassNode node = new ClassNode();
        node.setQualifiedName(qualifiedName);
        node.setSimpleName(simpleName);
        node.setServiceName(SERVICE);
        node.setFilePath("/repo/" + simpleName + ".java");
        node.setNodeType(NodeType.CLASS);
        node.setPackageName(qualifiedName.substring(0, qualifiedName.lastIndexOf('.')));
        return node;
    }

    private MethodNode methodNode(String simpleName, String qualifiedName, ClassNode owner) {
        MethodNode node = new MethodNode();
        node.setSimpleName(simpleName);
        node.setQualifiedName(qualifiedName);
        node.setClassName(owner.getSimpleName());
        node.setServiceName(SERVICE);
        node.setFilePath(owner.getFilePath());
        node.setEndpoint(false);
        node.setTransactional(false);
        return node;
    }

    private void save(Object... nodes) {
        List<ClassNode>  classes = java.util.Arrays.stream(nodes)
                .filter(ClassNode.class::isInstance).map(ClassNode.class::cast).toList();
        List<MethodNode> methods = java.util.Arrays.stream(nodes)
                .filter(MethodNode.class::isInstance).map(MethodNode.class::cast).toList();

        graphService.saveFileResult(new JavaFileProcessor.JavaFileResult(
                List.of(), List.of(), classes, methods, List.of(), List.of()));
    }
}
