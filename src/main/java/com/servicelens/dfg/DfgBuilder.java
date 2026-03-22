package com.servicelens.dfg;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Intra-procedural Data Flow Graph builder.
 *
 * Tracks how values are DEFINED and USED within a single method.
 * We use SSA-inspired (Static Single Assignment) naming to distinguish
 * multiple assignments to the same variable.
 *
 * WHAT WE TRACK:
 * ─────────────
 * DEF  → variable is assigned a value
 *         Types: PARAMETER, LOCAL_DECL, ASSIGNMENT, LOOP_VAR
 *
 * USE  → variable's value is read
 *         Types: METHOD_ARG, CONDITION, RETURN_VALUE, ASSIGNMENT_RHS
 *
 * EXAMPLE:
 * ─────────
 *   public PaymentResult process(Order order) {          // DEF: order (PARAMETER)
 *       double amount = order.getAmount();               // DEF: amount (LOCAL_DECL)
 *                                                        // USE: order (METHOD_ARG)
 *       if (amount > 0) {                               // USE: amount (CONDITION)
 *           PaymentResult r = gateway.charge(amount);   // DEF: r (LOCAL_DECL)
 *                                                        // USE: amount (METHOD_ARG)
 *           return r;                                   // USE: r (RETURN_VALUE)
 *       }
 *       return null;
 *   }
 *
 * WHAT THIS ENABLES FOR DEBUGGING:
 * ──────────────────────────────────
 * 1. "Where does the 'amount' value come from?"
 *    → Find DEF nodes for 'amount' → order.getAmount() at line 2
 *
 * 2. "What uses the 'amount' value?"
 *    → Find USE nodes for 'amount' → condition (line 3) + method arg (line 4)
 *
 * 3. "Could 'r' be null when returned?"
 *    → DEF of 'r' is gateway.charge() — what can that return?
 *    → If gateway.charge() can return null → null return is possible
 *
 * 4. "Is 'order' ever null-checked before use?"
 *    → Find all USE nodes for 'order'
 *    → Is there a CONDITION node checking 'order != null' before each USE?
 *    → If not → potential NPE
 */
@Component
public class DfgBuilder {

    private static final Logger log = LoggerFactory.getLogger(DfgBuilder.class);

    /**
     * Build the DFG for a method.
     * Returns a MethodDataFlow containing all variable definitions and uses.
     */
    public MethodDataFlow build(MethodDeclaration method,
                                String methodQualifiedName,
                                String serviceName) {
        MethodDataFlow flow = new MethodDataFlow(methodQualifiedName, serviceName);

        try {
            // Pass 1: Collect parameter definitions
            collectParameterDefs(method, flow);

            // Pass 2: Walk method body collecting all defs and uses
            method.getBody().ifPresent(body ->
                    collectFromBlock(body, flow));

        } catch (Exception e) {
            log.warn("DFG build failed for {}: {}", methodQualifiedName, e.getMessage());
        }

        return flow;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PARAMETER DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════

    private void collectParameterDefs(MethodDeclaration method, MethodDataFlow flow) {
        method.getParameters().forEach(param -> {
            flow.addDef(new DataFlowNode(
                    param.getNameAsString(),
                    param.getTypeAsString(),
                    DataFlowNode.DefType.PARAMETER,
                    null,                              // no source expression for parameters
                    method.getBegin().map(p -> p.line).orElse(0)
            ));
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BLOCK TRAVERSAL
    // ═══════════════════════════════════════════════════════════════════════

    private void collectFromBlock(BlockStmt block, MethodDataFlow flow) {
        block.getStatements().forEach(stmt -> collectFromStatement(stmt, flow));
    }

    private void collectFromStatement(Statement stmt, MethodDataFlow flow) {
        if (stmt instanceof ExpressionStmt exprStmt) {
            collectFromExpression(exprStmt.getExpression(), flow, null);

        } else if (stmt instanceof ReturnStmt returnStmt) {
            returnStmt.getExpression().ifPresent(expr -> {
                // The return value is a USE of whatever variable/expression is returned
                collectUseFromExpression(expr, flow, DataFlowUse.UseType.RETURN_VALUE,
                        stmt.getBegin().map(p -> p.line).orElse(0));
            });

        } else if (stmt instanceof IfStmt ifStmt) {
            // Condition is a USE
            collectUseFromExpression(ifStmt.getCondition(), flow,
                    DataFlowUse.UseType.CONDITION,
                    ifStmt.getBegin().map(p -> p.line).orElse(0));
            collectFromStatement(ifStmt.getThenStmt(), flow);
            ifStmt.getElseStmt().ifPresent(elseStmt -> collectFromStatement(elseStmt, flow));

        } else if (stmt instanceof WhileStmt whileStmt) {
            collectUseFromExpression(whileStmt.getCondition(), flow,
                    DataFlowUse.UseType.CONDITION,
                    whileStmt.getBegin().map(p -> p.line).orElse(0));
            collectFromStatement(whileStmt.getBody(), flow);

        } else if (stmt instanceof ForStmt forStmt) {
            forStmt.getCompare().ifPresent(cond ->
                    collectUseFromExpression(cond, flow, DataFlowUse.UseType.CONDITION,
                            forStmt.getBegin().map(p -> p.line).orElse(0)));
            collectFromStatement(forStmt.getBody(), flow);

        } else if (stmt instanceof ForEachStmt forEachStmt) {
            // Loop variable is a DEF
            forEachStmt.getVariable().getVariables().forEach(v ->
                    flow.addDef(new DataFlowNode(
                            v.getNameAsString(), v.getTypeAsString(),
                            DataFlowNode.DefType.LOOP_VAR, null,
                            forEachStmt.getBegin().map(p -> p.line).orElse(0)
                    ))
            );
            // Iterable is a USE
            collectUseFromExpression(forEachStmt.getIterable(), flow,
                    DataFlowUse.UseType.CONDITION,
                    forEachStmt.getBegin().map(p -> p.line).orElse(0));
            collectFromStatement(forEachStmt.getBody(), flow);

        } else if (stmt instanceof TryStmt tryStmt) {
            collectFromBlock(tryStmt.getTryBlock(), flow);
            tryStmt.getCatchClauses().forEach(catchClause -> {
                // Caught exception is a DEF
                flow.addDef(new DataFlowNode(
                        catchClause.getParameter().getNameAsString(),
                        catchClause.getParameter().getTypeAsString(),
                        DataFlowNode.DefType.CAUGHT_EXCEPTION, null,
                        catchClause.getBegin().map(p -> p.line).orElse(0)
                ));
                collectFromBlock(catchClause.getBody(), flow);
            });
            tryStmt.getFinallyBlock().ifPresent(fb -> collectFromBlock(fb, flow));

        } else if (stmt instanceof ThrowStmt throwStmt) {
            collectUseFromExpression(throwStmt.getExpression(), flow,
                    DataFlowUse.UseType.THROWN_VALUE,
                    throwStmt.getBegin().map(p -> p.line).orElse(0));

        } else if (stmt instanceof BlockStmt blockStmt) {
            collectFromBlock(blockStmt, flow);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EXPRESSION ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════

    private void collectFromExpression(Expression expr, MethodDataFlow flow,
                                       DataFlowUse.UseType useContext) {

        if (expr instanceof VariableDeclarationExpr varDecl) {
            // Local variable declaration — creates DEF(s)
            varDecl.getVariables().forEach(v -> {
                // The initializer is a USE
                v.getInitializer().ifPresent(init ->
                        collectUseFromExpression(init, flow,
                                DataFlowUse.UseType.ASSIGNMENT_RHS,
                                varDecl.getBegin().map(p -> p.line).orElse(0))
                );

                // The variable itself is a DEF
                String defSource = v.getInitializer()
                        .map(Object::toString).orElse(null);

                flow.addDef(new DataFlowNode(
                        v.getNameAsString(),
                        v.getTypeAsString(),
                        DataFlowNode.DefType.LOCAL_DECL,
                        defSource,
                        varDecl.getBegin().map(p -> p.line).orElse(0)
                ));
            });

        } else if (expr instanceof AssignExpr assignExpr) {
            // Assignment — target is DEF, value is USE
            String targetName = extractVariableName(assignExpr.getTarget());
            if (targetName != null) {
                collectUseFromExpression(assignExpr.getValue(), flow,
                        DataFlowUse.UseType.ASSIGNMENT_RHS,
                        assignExpr.getBegin().map(p -> p.line).orElse(0));

                flow.addDef(new DataFlowNode(
                        targetName, null,
                        DataFlowNode.DefType.ASSIGNMENT,
                        assignExpr.getValue().toString(),
                        assignExpr.getBegin().map(p -> p.line).orElse(0)
                ));
            }

        } else if (expr instanceof MethodCallExpr callExpr) {
            // Method call arguments are USEs
            callExpr.getArguments().forEach(arg ->
                    collectUseFromExpression(arg, flow,
                            DataFlowUse.UseType.METHOD_ARG,
                            callExpr.getBegin().map(p -> p.line).orElse(0))
            );
            // Scope (object being called on) is also a USE
            callExpr.getScope().ifPresent(scope ->
                    collectUseFromExpression(scope, flow,
                            DataFlowUse.UseType.METHOD_ARG,
                            callExpr.getBegin().map(p -> p.line).orElse(0))
            );

        } else {
            // For anything else, treat as a general USE
            if (useContext != null) {
                collectUseFromExpression(expr, flow, useContext,
                        expr.getBegin().map(p -> p.line).orElse(0));
            }
        }
    }

    /**
     * Extract variable name from expression (for assignment targets).
     * Handles: simple names (x), field access (this.x), array access (arr[i])
     */
    private String extractVariableName(Expression expr) {
        if (expr instanceof NameExpr nameExpr) {
            return nameExpr.getNameAsString();
        }
        if (expr instanceof FieldAccessExpr fieldExpr) {
            return fieldExpr.getScope().toString() + "." + fieldExpr.getNameAsString();
        }
        if (expr instanceof ArrayAccessExpr arrayExpr) {
            return arrayExpr.getName().toString();
        }
        return null;
    }

    /**
     * Collect USE nodes from an expression.
     * Recursively finds all NameExpr (variable references) in the expression.
     */
    private void collectUseFromExpression(Expression expr, MethodDataFlow flow,
                                          DataFlowUse.UseType useType, int line) {
        // Direct variable reference
        if (expr instanceof NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            // Skip obvious non-variables (class names start with uppercase
            // in Java convention — but this is heuristic)
            if (Character.isLowerCase(name.charAt(0)) || name.equals("this")) {
                flow.addUse(new DataFlowUse(name, useType, expr.toString(), line));
            }
            return;
        }

        // Field access: this.field or object.field
        if (expr instanceof FieldAccessExpr fieldExpr) {
            String fullRef = fieldExpr.toString();
            flow.addUse(new DataFlowUse(fullRef, useType, expr.toString(), line));
            return;
        }

        // Method call — recurse into arguments and scope
        if (expr instanceof MethodCallExpr callExpr) {
            callExpr.getScope().ifPresent(scope ->
                    collectUseFromExpression(scope, flow, useType, line));
            callExpr.getArguments().forEach(arg ->
                    collectUseFromExpression(arg, flow, DataFlowUse.UseType.METHOD_ARG, line));
            return;
        }

        // Binary expression — check both sides
        if (expr instanceof BinaryExpr binaryExpr) {
            collectUseFromExpression(binaryExpr.getLeft(), flow, useType, line);
            collectUseFromExpression(binaryExpr.getRight(), flow, useType, line);
            return;
        }

        // Unary expression (!, -, ++, --)
        if (expr instanceof UnaryExpr unaryExpr) {
            collectUseFromExpression(unaryExpr.getExpression(), flow, useType, line);
            return;
        }

        // Conditional (ternary): condition ? thenExpr : elseExpr
        if (expr instanceof ConditionalExpr condExpr) {
            collectUseFromExpression(condExpr.getCondition(), flow,
                    DataFlowUse.UseType.CONDITION, line);
            collectUseFromExpression(condExpr.getThenExpr(), flow, useType, line);
            collectUseFromExpression(condExpr.getElseExpr(), flow, useType, line);
            return;
        }

        // Cast expression
        if (expr instanceof CastExpr castExpr) {
            collectUseFromExpression(castExpr.getExpression(), flow, useType, line);
        }
    }
}
