package com.servicelens.dfg;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.*;

/**
 * Complete Data Flow Graph for a single method.
 *
 * Holds all variable DEFINITIONS and USES found in the method body.
 * Built by DfgBuilder and stored summarised in MethodNode.dataFlowSummary.
 *
 * KEY QUERIES THIS ENABLES:
 * ──────────────────────────
 * getDefsFor("order")    → where was 'order' assigned?
 * getUsesFor("order")    → where was 'order' read?
 * getExternalReferences()→ which fields/injected beans does this method use?
 *
 * The external references are especially powerful: if PaymentService
 * is injected as a field and processPayment() uses it,
 * then externalReferences contains "paymentService".
 * Impact analysis can find all methods that use a changing dependency.
 */
@Data
@RequiredArgsConstructor
public class MethodDataFlow {

    final String methodQualifiedName;
    final String serviceName;

    List<DataFlowNode> definitions = new ArrayList<>();
    List<DataFlowUse>  uses        = new ArrayList<>();

    void addDef(DataFlowNode def) { definitions.add(def); }
    void addUse(DataFlowUse use)  { uses.add(use); }

    public List<DataFlowNode> getDefsFor(String variableName) {
        return definitions.stream()
                .filter(d -> d.getVariableName().equals(variableName))
                .toList();
    }

    public List<DataFlowUse> getUsesFor(String variableName) {
        return uses.stream()
                .filter(u -> u.getVariableName().equals(variableName))
                .toList();
    }

    /**
     * Variables used but never locally defined = external references.
     * These are class fields or injected Spring beans.
     */
    public Set<String> getExternalReferences() {
        Set<String> defined = new HashSet<>();
        definitions.forEach(d -> defined.add(d.getVariableName()));

        Set<String> external = new HashSet<>();
        uses.stream()
                .map(DataFlowUse::getVariableName)
                .filter(name -> !defined.contains(name))
                .filter(name -> !name.equals("this"))
                .forEach(external::add);

        return external;
    }

    /**
     * Compact map for storage in MethodNode.dataFlowSummary.
     * Format: { varName: { defs: [...], uses: [...] } }
     */
    public Map<String, Object> toSummaryMap() {
        Map<String, Object> summary = new LinkedHashMap<>();

        Map<String, List<DataFlowNode>> defsByVar = new LinkedHashMap<>();
        definitions.forEach(d ->
                defsByVar.computeIfAbsent(d.getVariableName(), k -> new ArrayList<>()).add(d));

        Map<String, List<DataFlowUse>> usesByVar = new LinkedHashMap<>();
        uses.forEach(u ->
                usesByVar.computeIfAbsent(u.getVariableName(), k -> new ArrayList<>()).add(u));

        Set<String> allVars = new LinkedHashSet<>();
        allVars.addAll(defsByVar.keySet());
        allVars.addAll(usesByVar.keySet());

        allVars.forEach(var -> {
            Map<String, Object> varInfo = new LinkedHashMap<>();

            List<Map<String, Object>> defList = defsByVar.getOrDefault(var, List.of()).stream()
                    .map(d -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type", d.getDefType().name());
                        m.put("line", d.getLine());
                        if (d.getSourceExpression() != null) m.put("from", d.getSourceExpression());
                        if (d.getVariableType() != null)     m.put("varType", d.getVariableType());
                        return m;
                    }).toList();

            List<Map<String, Object>> useList = usesByVar.getOrDefault(var, List.of()).stream()
                    .map(u -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("type",    u.getUseType().name());
                        m.put("line",    u.getLine());
                        m.put("context", u.getExpressionContext());
                        return m;
                    }).toList();

            if (!defList.isEmpty()) varInfo.put("defs", defList);
            if (!useList.isEmpty()) varInfo.put("uses", useList);
            if (!varInfo.isEmpty()) summary.put(var, varInfo);
        });

        return summary;
    }
}