package com.servicelens.cfg;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Control Flow Graph for a single Java method.
 *
 * APPROACH:
 * We perform a single pass over the method's statement AST.
 * Each statement type creates specific node(s) and edge(s).
 *
 * We use a "current block" pointer that accumulates statements.
 * When we hit a branch (if/while/try), we:
 *   1. Finalise the current block
 *   2. Create branch nodes
 *   3. Recurse into each branch
 *   4. Create a merge node where branches rejoin
 *
 * WHAT THIS ENABLES FOR DEBUGGING:
 * ─────────────────────────────────
 * 1. "Under what condition does retryPayment() get called?"
 *    → Walk CFG backward from the retryPayment() METHOD_CALL node
 *    → Collect all CONDITION nodes on paths leading to it
 *    → The labels of those edges (TRUE/FALSE) tell you the conditions
 *
 * 2. "Can a NullPointerException escape this method?"
 *    → Find all EXCEPTION_THROW nodes
 *    → Walk forward — is there a EXCEPTION_HANDLER for NPE on every path?
 *    → If no → NPE can escape
 *
 * 3. "What is the worst-case execution path length?"
 *    → Find the longest path from ENTRY to EXIT
 *    → Sum up METHOD_CALL nodes → each is a potential performance concern
 *
 * 4. "Is there dead code in this method?"
 *    → Find STATEMENT/METHOD_CALL nodes with no incoming edges
 *      (except ENTRY) → unreachable code
 */
@Component
public class CfgBuilder {

    private static final Logger log = LoggerFactory.getLogger(CfgBuilder.class);

    /**
     * Build CFG for a method.
     * Returns all nodes — the first is always ENTRY, last is always EXIT.
     */
    public List<CfgNode> build(
            MethodDeclaration method,
            String methodQualifiedName,
            String serviceName) {

        List<CfgNode> allNodes = new ArrayList<>();

        try {
            // Create ENTRY and EXIT nodes first
            CfgNode entry = createNode(CfgNode.CfgNodeType.ENTRY,
                    "METHOD_ENTRY: " + method.getNameAsString(),
                    method.getBegin().map(p -> p.line).orElse(0),
                    method.getBegin().map(p -> p.line).orElse(0),
                    methodQualifiedName, serviceName);

            CfgNode exit = createNode(CfgNode.CfgNodeType.EXIT,
                    "METHOD_EXIT: " + method.getNameAsString(),
                    method.getEnd().map(p -> p.line).orElse(0),
                    method.getEnd().map(p -> p.line).orElse(0),
                    methodQualifiedName, serviceName);

            allNodes.add(entry);

            // Process the method body
            if (method.getBody().isPresent()) {
                BlockStmt body = method.getBody().get();
                CfgNode lastNode = processBlock(body, entry, exit,
                        allNodes, methodQualifiedName, serviceName);

                // Connect last node to exit if not already connected
                if (lastNode != exit && !hasEdgeTo(lastNode, exit)) {
                    addEdge(lastNode, exit, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
                }
            } else {
                // Abstract method or interface method — direct entry→exit
                addEdge(entry, exit, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
            }

            allNodes.add(exit);

        } catch (Exception e) {
            log.warn("CFG build failed for {}: {}", methodQualifiedName, e.getMessage());
        }

        return allNodes;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BLOCK PROCESSING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Process a block of statements.
     * Returns the last node in the block (so caller can connect what comes next).
     */
    private CfgNode processBlock(
            BlockStmt block,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        CfgNode current = predecessor;

        for (Statement stmt : block.getStatements()) {
            current = processStatement(stmt, current, exitNode,
                    allNodes, methodQN, serviceName);
        }

        return current;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATEMENT DISPATCH
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processStatement(
            Statement stmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        // Dispatch to specific handler based on statement type
        if (stmt instanceof IfStmt ifStmt) {
            return processIf(ifStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof WhileStmt whileStmt) {
            return processWhile(whileStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof ForStmt forStmt) {
            return processFor(forStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof ForEachStmt forEachStmt) {
            return processForEach(forEachStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof TryStmt tryStmt) {
            return processTry(tryStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof ReturnStmt returnStmt) {
            return processReturn(returnStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof ThrowStmt throwStmt) {
            return processThrow(throwStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof SwitchStmt switchStmt) {
            return processSwitch(switchStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }
        if (stmt instanceof BlockStmt blockStmt) {
            return processBlock(blockStmt, predecessor, exitNode, allNodes, methodQN, serviceName);
        }

        // Default: regular statement — check if it contains a method call
        return processGenericStatement(stmt, predecessor, allNodes, methodQN, serviceName);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // IF STATEMENT
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processIf(
            IfStmt ifStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        // Create the condition node
        CfgNode conditionNode = createNode(
                CfgNode.CfgNodeType.CONDITION,
                "if (" + ifStmt.getCondition().toString() + ")",
                ifStmt.getBegin().map(p -> p.line).orElse(0),
                ifStmt.getBegin().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        conditionNode.setCondition(ifStmt.getCondition().toString());
        allNodes.add(conditionNode);

        // Connect predecessor → condition
        addEdge(predecessor, conditionNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);

        // Process THEN branch
        CfgNode thenLast = processStatement(
                ifStmt.getThenStmt(), conditionNode, exitNode, allNodes, methodQN, serviceName
        );
        // The edge from conditionNode to thenLast's first statement was added
        // by processStatement. We need to mark the first edge as TRUE.
        markFirstEdge(conditionNode, CfgEdge.EdgeType.TRUE,
                ifStmt.getCondition().toString());

        // Create merge node (where both branches rejoin)
        CfgNode mergeNode = createNode(
                CfgNode.CfgNodeType.STATEMENT, "merge",
                ifStmt.getEnd().map(p -> p.line).orElse(0),
                ifStmt.getEnd().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(mergeNode);

        // Connect THEN last → merge
        if (thenLast != exitNode) {
            addEdge(thenLast, mergeNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
        }

        // Process ELSE branch if present
        if (ifStmt.getElseStmt().isPresent()) {
            CfgNode elseLast = processStatement(
                    ifStmt.getElseStmt().get(), conditionNode, exitNode, allNodes, methodQN, serviceName
            );
            markLastEdgeFrom(conditionNode, CfgEdge.EdgeType.FALSE,
                    "!(" + ifStmt.getCondition().toString() + ")");

            if (elseLast != exitNode) {
                addEdge(elseLast, mergeNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
            }
        } else {
            // No else — false branch goes directly to merge
            addEdge(conditionNode, mergeNode, CfgEdge.EdgeType.FALSE,
                    "!(" + ifStmt.getCondition().toString() + ")", null);
        }

        return mergeNode;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WHILE LOOP
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processWhile(
            WhileStmt whileStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        // Loop header = condition check
        CfgNode loopHeader = createNode(
                CfgNode.CfgNodeType.LOOP_HEADER,
                "while (" + whileStmt.getCondition().toString() + ")",
                whileStmt.getBegin().map(p -> p.line).orElse(0),
                whileStmt.getBegin().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        loopHeader.setCondition(whileStmt.getCondition().toString());
        allNodes.add(loopHeader);

        addEdge(predecessor, loopHeader, CfgEdge.EdgeType.UNCONDITIONAL, null, null);

        // Process loop body
        CfgNode bodyLast = processStatement(
                whileStmt.getBody(), loopHeader, exitNode, allNodes, methodQN, serviceName
        );
        markFirstEdge(loopHeader, CfgEdge.EdgeType.TRUE,
                whileStmt.getCondition().toString());

        // Loop back edge: body end → header
        if (bodyLast != exitNode) {
            addEdge(bodyLast, loopHeader, CfgEdge.EdgeType.LOOP_BACK, null, null);
        }

        // Exit node: condition false → after loop
        CfgNode afterLoop = createNode(
                CfgNode.CfgNodeType.STATEMENT, "after_while",
                whileStmt.getEnd().map(p -> p.line).orElse(0),
                whileStmt.getEnd().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(afterLoop);

        addEdge(loopHeader, afterLoop, CfgEdge.EdgeType.FALSE,
                "!(" + whileStmt.getCondition().toString() + ")", null);

        return afterLoop;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FOR LOOP
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processFor(
            ForStmt forStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        String condition = forStmt.getCompare()
                .map(Object::toString).orElse("true");

        CfgNode loopHeader = createNode(
                CfgNode.CfgNodeType.LOOP_HEADER,
                "for (" + condition + ")",
                forStmt.getBegin().map(p -> p.line).orElse(0),
                forStmt.getBegin().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        loopHeader.setCondition(condition);
        allNodes.add(loopHeader);

        addEdge(predecessor, loopHeader, CfgEdge.EdgeType.UNCONDITIONAL, null, null);

        CfgNode bodyLast = processStatement(
                forStmt.getBody(), loopHeader, exitNode, allNodes, methodQN, serviceName
        );
        markFirstEdge(loopHeader, CfgEdge.EdgeType.TRUE, condition);

        if (bodyLast != exitNode) {
            addEdge(bodyLast, loopHeader, CfgEdge.EdgeType.LOOP_BACK, null, null);
        }

        CfgNode afterLoop = createNode(
                CfgNode.CfgNodeType.STATEMENT, "after_for",
                forStmt.getEnd().map(p -> p.line).orElse(0),
                forStmt.getEnd().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(afterLoop);
        addEdge(loopHeader, afterLoop, CfgEdge.EdgeType.FALSE,
                "!(" + condition + ")", null);

        return afterLoop;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FOREACH LOOP
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processForEach(
            ForEachStmt forEachStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        String iterCondition = "hasNext(" + forEachStmt.getIterable().toString() + ")";

        CfgNode loopHeader = createNode(
                CfgNode.CfgNodeType.LOOP_HEADER,
                "for (" + forEachStmt.getVariable() + " : " + forEachStmt.getIterable() + ")",
                forEachStmt.getBegin().map(p -> p.line).orElse(0),
                forEachStmt.getBegin().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        loopHeader.setCondition(iterCondition);
        allNodes.add(loopHeader);

        addEdge(predecessor, loopHeader, CfgEdge.EdgeType.UNCONDITIONAL, null, null);

        CfgNode bodyLast = processStatement(
                forEachStmt.getBody(), loopHeader, exitNode, allNodes, methodQN, serviceName
        );
        markFirstEdge(loopHeader, CfgEdge.EdgeType.TRUE, iterCondition);

        if (bodyLast != exitNode) {
            addEdge(bodyLast, loopHeader, CfgEdge.EdgeType.LOOP_BACK, null, null);
        }

        CfgNode afterLoop = createNode(
                CfgNode.CfgNodeType.STATEMENT, "after_foreach",
                forEachStmt.getEnd().map(p -> p.line).orElse(0),
                forEachStmt.getEnd().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(afterLoop);
        addEdge(loopHeader, afterLoop, CfgEdge.EdgeType.FALSE,
                "!" + iterCondition, null);

        return afterLoop;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TRY-CATCH-FINALLY
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processTry(
            TryStmt tryStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        // Process try body
        CfgNode tryLast = processBlock(
                tryStmt.getTryBlock(), predecessor, exitNode, allNodes, methodQN, serviceName
        );

        // Create a merge node for after all catch/finally
        CfgNode mergeNode = createNode(
                CfgNode.CfgNodeType.STATEMENT, "try_merge",
                tryStmt.getEnd().map(p -> p.line).orElse(0),
                tryStmt.getEnd().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(mergeNode);

        // Normal path: try completes → merge (or finally)
        if (tryLast != exitNode) {
            addEdge(tryLast, mergeNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
        }

        // Process each catch block
        tryStmt.getCatchClauses().forEach(catchClause -> {
            String exType = catchClause.getParameter().getTypeAsString();

            CfgNode handlerEntry = createNode(
                    CfgNode.CfgNodeType.EXCEPTION_HANDLER,
                    "catch (" + exType + " " + catchClause.getParameter().getNameAsString() + ")",
                    catchClause.getBegin().map(p -> p.line).orElse(0),
                    catchClause.getBegin().map(p -> p.line).orElse(0),
                    methodQN, serviceName
            );
            handlerEntry.setExceptionType(exType);
            allNodes.add(handlerEntry);

            // Exception edge from try body predecessor → handler
            // (simplified: we draw from the try entry, not each statement)
            addEdge(predecessor, handlerEntry, CfgEdge.EdgeType.EXCEPTION, null, exType);

            // Process catch body
            CfgNode catchLast = processBlock(
                    catchClause.getBody(), handlerEntry, exitNode, allNodes, methodQN, serviceName
            );

            if (catchLast != exitNode) {
                addEdge(catchLast, mergeNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
            }
        });

        // Process finally block if present
        if (tryStmt.getFinallyBlock().isPresent()) {
            CfgNode finallyEntry = createNode(
                    CfgNode.CfgNodeType.FINALLY_BLOCK, "finally",
                    tryStmt.getFinallyBlock().get().getBegin().map(p -> p.line).orElse(0),
                    tryStmt.getFinallyBlock().get().getEnd().map(p -> p.line).orElse(0),
                    methodQN, serviceName
            );
            allNodes.add(finallyEntry);
            addEdge(mergeNode, finallyEntry, CfgEdge.EdgeType.UNCONDITIONAL, null, null);

            CfgNode finallyLast = processBlock(
                    tryStmt.getFinallyBlock().get(), finallyEntry, exitNode, allNodes, methodQN, serviceName
            );

            // Create a new merge after finally
            CfgNode afterFinally = createNode(
                    CfgNode.CfgNodeType.STATEMENT, "after_finally",
                    tryStmt.getEnd().map(p -> p.line).orElse(0),
                    tryStmt.getEnd().map(p -> p.line).orElse(0),
                    methodQN, serviceName
            );
            allNodes.add(afterFinally);

            if (finallyLast != exitNode) {
                addEdge(finallyLast, afterFinally, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
            }

            return afterFinally;
        }

        return mergeNode;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RETURN STATEMENT
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processReturn(
            ReturnStmt returnStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        String returnExpr = returnStmt.getExpression()
                .map(Object::toString).orElse("void");

        CfgNode returnNode = createNode(
                CfgNode.CfgNodeType.STATEMENT,
                "return " + returnExpr,
                returnStmt.getBegin().map(p -> p.line).orElse(0),
                returnStmt.getBegin().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(returnNode);

        addEdge(predecessor, returnNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
        addEdge(returnNode, exitNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);

        return exitNode;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THROW STATEMENT
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processThrow(
            ThrowStmt throwStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        CfgNode throwNode = createNode(
                CfgNode.CfgNodeType.EXCEPTION_THROW,
                "throw " + throwStmt.getExpression().toString(),
                throwStmt.getBegin().map(p -> p.line).orElse(0),
                throwStmt.getBegin().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(throwNode);

        addEdge(predecessor, throwNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
        // Throw exits the method (unless caught above)
        addEdge(throwNode, exitNode, CfgEdge.EdgeType.EXCEPTION, null,
                throwStmt.getExpression().toString());

        return exitNode;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SWITCH STATEMENT
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processSwitch(
            SwitchStmt switchStmt,
            CfgNode predecessor,
            CfgNode exitNode,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        CfgNode switchHeader = createNode(
                CfgNode.CfgNodeType.CONDITION,
                "switch (" + switchStmt.getSelector().toString() + ")",
                switchStmt.getBegin().map(p -> p.line).orElse(0),
                switchStmt.getBegin().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        switchHeader.setCondition(switchStmt.getSelector().toString());
        allNodes.add(switchHeader);

        addEdge(predecessor, switchHeader, CfgEdge.EdgeType.UNCONDITIONAL, null, null);

        CfgNode mergeNode = createNode(
                CfgNode.CfgNodeType.STATEMENT, "switch_merge",
                switchStmt.getEnd().map(p -> p.line).orElse(0),
                switchStmt.getEnd().map(p -> p.line).orElse(0),
                methodQN, serviceName
        );
        allNodes.add(mergeNode);

        // Process each case
        switchStmt.getEntries().forEach(entry -> {
            String caseLabel = entry.getLabels().isEmpty()
                    ? "default" : entry.getLabels().get(0).toString();

            CfgNode caseNode = createNode(
                    CfgNode.CfgNodeType.STATEMENT,
                    "case " + caseLabel,
                    entry.getBegin().map(p -> p.line).orElse(0),
                    entry.getEnd().map(p -> p.line).orElse(0),
                    methodQN, serviceName
            );
            allNodes.add(caseNode);
            addEdge(switchHeader, caseNode, CfgEdge.EdgeType.FALL_THROUGH,
                    switchStmt.getSelector() + " == " + caseLabel, null);

            // Process case statements
            CfgNode caseLast = caseNode;
            for (Statement stmt : entry.getStatements()) {
                caseLast = processStatement(stmt, caseLast, exitNode,
                        allNodes, methodQN, serviceName);
            }

            if (caseLast != exitNode) {
                addEdge(caseLast, mergeNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
            }
        });

        return mergeNode;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GENERIC STATEMENT (method calls, assignments, etc.)
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode processGenericStatement(
            Statement stmt,
            CfgNode predecessor,
            List<CfgNode> allNodes,
            String methodQN,
            String serviceName) {

        // Check if this statement contains a method call
        List<MethodCallExpr> calls = stmt.findAll(MethodCallExpr.class);

        if (!calls.isEmpty()) {
            // Create a METHOD_CALL node for the first/primary call
            MethodCallExpr primaryCall = calls.get(0);
            CfgNode callNode = createNode(
                    CfgNode.CfgNodeType.METHOD_CALL,
                    stmt.toString().trim(),
                    stmt.getBegin().map(p -> p.line).orElse(0),
                    stmt.getEnd().map(p -> p.line).orElse(0),
                    methodQN, serviceName
            );
            callNode.setCalledMethod(primaryCall.getNameAsString());
            allNodes.add(callNode);
            addEdge(predecessor, callNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
            return callNode;
        }

        // Regular statement — only create a node if meaningful
        String stmtText = stmt.toString().trim();
        if (stmtText.length() > 1 && !stmtText.equals(";")) {
            CfgNode stmtNode = createNode(
                    CfgNode.CfgNodeType.STATEMENT,
                    stmtText.length() > 80 ? stmtText.substring(0, 80) + "..." : stmtText,
                    stmt.getBegin().map(p -> p.line).orElse(0),
                    stmt.getEnd().map(p -> p.line).orElse(0),
                    methodQN, serviceName
            );
            allNodes.add(stmtNode);
            addEdge(predecessor, stmtNode, CfgEdge.EdgeType.UNCONDITIONAL, null, null);
            return stmtNode;
        }

        return predecessor;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════

    private CfgNode createNode(CfgNode.CfgNodeType type, String code,
                               int startLine, int endLine,
                               String methodQN, String serviceName) {
        CfgNode node = new CfgNode();
        node.setNodeType(type);
        node.setCode(code);
        node.setStartLine(startLine);
        node.setEndLine(endLine);
        node.setMethodQualifiedName(methodQN);
        node.setServiceName(serviceName);
        return node;
    }

    private void addEdge(CfgNode from, CfgNode to,
                         CfgEdge.EdgeType edgeType,
                         String condition, String exceptionType) {
        CfgEdge edge = new CfgEdge();
        edge.setEdgeType(edgeType);
        edge.setConditionText(condition);
        edge.setExceptionType(exceptionType);
        edge.setTarget(to);
        from.getSuccessors().add(edge);
    }

    private boolean hasEdgeTo(CfgNode from, CfgNode to) {
        return from.getSuccessors().stream()
                .anyMatch(e -> e.getTarget() == to);
    }

    /** Mark the first outgoing edge of a node with a specific type */
    private void markFirstEdge(CfgNode node, CfgEdge.EdgeType type, String condition) {
        if (!node.getSuccessors().isEmpty()) {
            CfgEdge first = node.getSuccessors().get(0);
            first.setEdgeType(type);
            first.setConditionText(condition);
        }
    }

    /** Mark the last outgoing edge of a node with a specific type */
    private void markLastEdgeFrom(CfgNode node, CfgEdge.EdgeType type, String condition) {
        List<CfgEdge> edges = node.getSuccessors();
        if (!edges.isEmpty()) {
            CfgEdge last = edges.get(edges.size() - 1);
            last.setEdgeType(type);
            last.setConditionText(condition);
        }
    }
}