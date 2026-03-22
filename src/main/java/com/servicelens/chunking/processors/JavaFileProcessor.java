package com.servicelens.chunking.processors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.servicelens.cfg.CfgBuilder;
import com.servicelens.cfg.CfgNode;
import com.servicelens.chunking.CodeChunk;
import com.servicelens.chunking.FileProcessor;
import com.servicelens.dfg.DfgBuilder;
import com.servicelens.dfg.MethodDataFlow;
import com.servicelens.documentation.DocumentationExtractor;
import com.servicelens.graph.domain.ClassNode;
import com.servicelens.graph.domain.MethodCallRelationship;
import com.servicelens.graph.domain.MethodNode;
import com.servicelens.graph.domain.NodeType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Production-grade Java file processor.
 *
 * In a SINGLE AST PASS this produces:
 *
 * 1. CodeChunk objects (for pgvector embedding)
 *    - Content enriched with: Javadoc + inline comments + metadata header
 *
 * 2. Separate documentation Document objects (for pgvector)
 *    - Javadoc-only chunks for "why" questions
 *
 * 3. ClassNode + MethodNode graph objects (for Neo4j)
 *    - INHERITS, IMPLEMENTS, DEPENDS_ON, DEFINES, CALLS, OVERRIDES
 *
 * 4. CFG nodes per method (for Neo4j)
 *    - Every branch, loop, try/catch represented as graph nodes+edges
 *
 * 5. MethodDataFlow per method (stored in MethodNode)
 *    - Variable definition-use chains for null safety analysis
 */
@Component
@RequiredArgsConstructor
public class JavaFileProcessor implements FileProcessor {

    private static final Logger log = LoggerFactory.getLogger(JavaFileProcessor.class);

    private final CfgBuilder cfgBuilder;
    private final DfgBuilder dfgBuilder;
    private final DocumentationExtractor docExtractor;

    private static final Set<String> HTTP_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping",
            "PatchMapping", "RequestMapping"
    );

    private static final Map<String, String> SPRING_STEREOTYPES = Map.of(
            "Service", "@Service", "Component", "@Component",
            "Controller", "@Controller", "RestController", "@RestController",
            "Repository", "@Repository", "Configuration", "@Configuration"
    );

    private static final Set<String> SKIP_METHODS = Set.of(
            "equals", "hashCode", "toString", "canEqual"
    );

    /**
     * Instance-based parser configured for Java 18 language level.
     *
     * <p>Unlike {@code StaticJavaParser} (which relies on mutable global state that
     * can be reset by other code), this instance is created once at construction time
     * and guarantees text blocks, records, sealed classes, and other modern syntax are
     * always supported. JAVA_18 is the highest level supported by javaparser 3.25.10.
     */
    private final JavaParser javaParser = new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_18));

    // ─── Full result container ─────────────────────────────────────────────

    public record JavaFileResult(
            List<CodeChunk> chunks,
            List<Document> documentationChunks,   // separate Javadoc docs for embedding
            List<ClassNode> classNodes,
            List<MethodNode> methodNodes,
            List<CfgNode> cfgNodes,               // all CFG nodes from all methods
            List<MethodDataFlow> dataFlows         // DFG per method
    ) {
        public static JavaFileResult empty() {
            return new JavaFileResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    // ─── FileProcessor interface ───────────────────────────────────────────

    @Override
    public boolean supports(Path file) {
        return file.toString().endsWith(".java");
    }

    @Override
    public List<CodeChunk> process(Path file, String serviceName) {
        return processFile(file, serviceName).chunks();
    }

    /**
     * Full processing — single AST walk, all outputs.
     */
    public JavaFileResult processFile(Path file, String serviceName) {
        List<CodeChunk> chunks          = new ArrayList<>();
        List<Document> docChunks        = new ArrayList<>();
        List<ClassNode> classNodes      = new ArrayList<>();
        List<MethodNode> methodNodes    = new ArrayList<>();
        List<CfgNode> allCfgNodes       = new ArrayList<>();
        List<MethodDataFlow> dataFlows  = new ArrayList<>();

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(file);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                log.warn("Failed to parse: {} — {}", file.getFileName(),
                        parseResult.getProblems().isEmpty() ? "unknown error"
                                : parseResult.getProblems().get(0).getMessage());
                return JavaFileResult.empty();
            }
            CompilationUnit cu = parseResult.getResult().get();
            boolean isTest     = isTestFile(file);
            Map<String, String> importMap = buildImportMap(cu);

            cu.findAll(TypeDeclaration.class).forEach(typeDecl -> {

                if (typeDecl instanceof ClassOrInterfaceDeclaration coid) {
                    if (coid.isInterface()) {
                        processInterface(coid, file, serviceName, importMap,
                                chunks, docChunks, classNodes, methodNodes,
                                allCfgNodes, dataFlows);
                    } else {
                        processClass(coid, file, serviceName, importMap, isTest,
                                chunks, docChunks, classNodes, methodNodes,
                                allCfgNodes, dataFlows);
                    }
                } else if (typeDecl instanceof EnumDeclaration enumDecl) {
                    processEnum(enumDecl, file, serviceName, chunks, classNodes);

                } else if (typeDecl instanceof RecordDeclaration recordDecl) {
                    processRecord(recordDecl, file, serviceName, importMap,
                            chunks, docChunks, classNodes, methodNodes,
                            allCfgNodes, dataFlows);

                } else if (typeDecl instanceof AnnotationDeclaration annotDecl) {
                    processAnnotationType(annotDecl, file, serviceName, classNodes);
                }
            });

        } catch (Exception e) {
            log.warn("Failed to parse: {} — {}", file.getFileName(), e.getMessage());
        }

        return new JavaFileResult(chunks, docChunks, classNodes, methodNodes,
                allCfgNodes, dataFlows);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLASS
    // ═══════════════════════════════════════════════════════════════════════

    private void processClass(
            ClassOrInterfaceDeclaration classDecl,
            Path file, String serviceName,
            Map<String, String> importMap, boolean isTest,
            List<CodeChunk> chunks, List<Document> docChunks,
            List<ClassNode> classNodes, List<MethodNode> methodNodes,
            List<CfgNode> allCfgNodes, List<MethodDataFlow> dataFlows) {

        String packageName   = extractPackageName(classDecl);
        String className     = classDecl.getNameAsString();
        String qualifiedName = fqn(packageName, className);

        // ── Extract class documentation ────────────────────────────────────
        DocumentationExtractor.ClassDocumentation classDoc =
                docExtractor.extractClassDoc(classDecl);

        // ── Build ClassNode ────────────────────────────────────────────────
        ClassNode classNode = buildClassNode(classDecl, qualifiedName, packageName,
                className, file, serviceName, importMap, classDoc);

        // ── Process methods ────────────────────────────────────────────────
        String classLevelMapping = extractClassLevelMapping(classDecl);

        classDecl.getMethods().forEach(method -> {
            MethodNode methodNode = processMethod(method, classDecl, qualifiedName,
                    packageName, file, serviceName, importMap, classLevelMapping,
                    isTest, classDoc, chunks, docChunks, allCfgNodes, dataFlows);
            classNode.getMethods().add(methodNode);
            methodNodes.add(methodNode);
        });

        classDecl.getConstructors().forEach(ctor ->
                processConstructor(ctor, qualifiedName, packageName,
                        file, serviceName, chunks, methodNodes));

        classNodes.add(classNode);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERFACE
    // ═══════════════════════════════════════════════════════════════════════

    private void processInterface(
            ClassOrInterfaceDeclaration ifaceDecl,
            Path file, String serviceName,
            Map<String, String> importMap,
            List<CodeChunk> chunks, List<Document> docChunks,
            List<ClassNode> classNodes, List<MethodNode> methodNodes,
            List<CfgNode> allCfgNodes, List<MethodDataFlow> dataFlows) {

        String packageName   = extractPackageName(ifaceDecl);
        String ifaceName     = ifaceDecl.getNameAsString();
        String qualifiedName = fqn(packageName, ifaceName);

        DocumentationExtractor.ClassDocumentation classDoc =
                docExtractor.extractClassDoc(ifaceDecl);

        ClassNode ifaceNode = new ClassNode();
        ifaceNode.setQualifiedName(qualifiedName);
        ifaceNode.setSimpleName(ifaceName);
        ifaceNode.setPackageName(packageName);
        ifaceNode.setFilePath(file.toString());
        ifaceNode.setServiceName(serviceName);
        ifaceNode.setInterface(true);
        ifaceNode.setNodeType(NodeType.INTERFACE);
        ifaceNode.setAnnotations(extractAnnotationNames(ifaceDecl));

        ifaceDecl.getExtendedTypes().forEach(ext -> {
            ClassNode parent = new ClassNode();
            parent.setQualifiedName(resolveType(ext.getNameAsString(), packageName, importMap));
            parent.setSimpleName(ext.getNameAsString());
            parent.setInterface(true);
            ifaceNode.getImplementedInterfaces().add(parent);
        });

        ifaceDecl.getMethods().forEach(method -> {
            MethodNode mn = processMethod(method, ifaceDecl, qualifiedName,
                    packageName, file, serviceName, importMap, "", false,
                    classDoc, chunks, docChunks, allCfgNodes, dataFlows);
            ifaceNode.getMethods().add(mn);
            methodNodes.add(mn);
        });

        classNodes.add(ifaceNode);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // METHOD — the most complex part
    // ═══════════════════════════════════════════════════════════════════════

    private MethodNode processMethod(
            MethodDeclaration method,
            TypeDeclaration<?> ownerType,
            String ownerQN, String packageName,
            Path file, String serviceName,
            Map<String, String> importMap,
            String classLevelMapping,
            boolean isTest,
            DocumentationExtractor.ClassDocumentation classDoc,
            List<CodeChunk> chunks,
            List<Document> docChunks,
            List<CfgNode> allCfgNodes,
            List<MethodDataFlow> dataFlows) {

        String methodName = method.getNameAsString();
        String paramSig   = buildParamSignature(method);
        String qualifiedName = ownerQN + "." + methodName + "(" + paramSig + ")";

        int startLine = method.getBegin().map(p -> p.line).orElse(0);
        int endLine   = method.getEnd().map(p -> p.line).orElse(0);

        // ── Extract documentation ──────────────────────────────────────────
        DocumentationExtractor.MethodDocumentation methodDoc =
                docExtractor.extractMethodDoc(method);

        // ── Build MethodNode ───────────────────────────────────────────────
        MethodNode methodNode = new MethodNode();
        methodNode.setQualifiedName(qualifiedName);
        methodNode.setSimpleName(methodName);
        methodNode.setClassName(ownerType.getNameAsString());
        methodNode.setPackageName(packageName);
        methodNode.setFilePath(file.toString());
        methodNode.setServiceName(serviceName);
        methodNode.setReturnType(method.getTypeAsString());
        methodNode.setParameterSignature(paramSig);
        methodNode.setStartLine(startLine);
        methodNode.setEndLine(endLine);
        methodNode.setContent(method.toString());
        methodNode.setTestMethod(isTest);
        methodNode.setAnnotations(extractAnnotationNames(method));

        // ── Store Javadoc summary on node ──────────────────────────────────
        if (methodDoc.getDescription() != null) {
            methodNode.setJavadocSummary(methodDoc.getDescription());
        }

        // ── Spring annotation enrichment ───────────────────────────────────
        Map<String, String> extra = new HashMap<>();
        enrichWithSpringAnnotations(method, methodNode, extra, classLevelMapping);

        // ── CALLS relationship — find all method calls ─────────────────────
        extractMethodCalls(method, methodNode, ownerQN, packageName, importMap);

        // ── CFG: build control flow graph ──────────────────────────────────
        if (method.getBody().isPresent() && !isTest) {
            List<CfgNode> cfgNodes = cfgBuilder.build(method, qualifiedName, serviceName);
            allCfgNodes.addAll(cfgNodes);
            methodNode.setCfgNodeCount(cfgNodes.size());
            // Store branch complexity (number of CONDITION nodes = cyclomatic complexity)
            long branchCount = cfgNodes.stream()
                    .filter(n -> n.getNodeType() == CfgNode.CfgNodeType.CONDITION
                            || n.getNodeType() == CfgNode.CfgNodeType.LOOP_HEADER)
                    .count();
            methodNode.setCyclomaticComplexity((int) branchCount + 1);
        }

        // ── DFG: build data flow graph ─────────────────────────────────────
        if (method.getBody().isPresent()) {
            MethodDataFlow dataFlow = dfgBuilder.build(method, qualifiedName, serviceName);
            dataFlows.add(dataFlow);
            // Store DFG summary as JSON-compatible map on the node
            methodNode.setDataFlowSummary(dataFlow.toSummaryMap());
            // Store external references (injected/field dependencies used in method)
            methodNode.setExternalReferences(new ArrayList<>(dataFlow.getExternalReferences()));
        }

        // ── Build CodeChunk for vector embedding ───────────────────────────
        boolean isTrivial = (endLine - startLine < 3) || SKIP_METHODS.contains(methodName);

        if (!isTrivial) {
            // Build enriched content: Javadoc + code
            String enrichedContent = docExtractor.buildEnrichedMethodContent(
                    method, methodDoc, classDoc, method.toString());

            extra.put("return_type", method.getTypeAsString());
            extra.put("class_name", ownerType.getNameAsString());
            extra.put("param_signature", paramSig);
            extra.put("cyclomatic_complexity",
                    String.valueOf(methodNode.getCyclomaticComplexity()));
            if (methodDoc.hasAnyDoc()) extra.put("has_javadoc", "true");

            chunks.add(new CodeChunk(
                    enrichedContent,           // ← enriched with Javadoc
                    file.toString(), methodName,
                    startLine, endLine,
                    "java", "JAVA",
                    isTest ? CodeChunk.ChunkType.TEST : CodeChunk.ChunkType.CODE,
                    serviceName, extra
            ));

            // ── Build separate documentation chunk ─────────────────────────
            // Only if method has meaningful Javadoc
            docExtractor.buildDocumentationChunk(
                    methodDoc, qualifiedName, file.toString(), serviceName
            ).ifPresent(docChunks::add);
        }

        return methodNode;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ENUM
    // ═══════════════════════════════════════════════════════════════════════

    private void processEnum(
            EnumDeclaration enumDecl,
            Path file, String serviceName,
            List<CodeChunk> chunks, List<ClassNode> classNodes) {

        String packageName   = extractPackageName(enumDecl);
        String enumName      = enumDecl.getNameAsString();
        String qualifiedName = fqn(packageName, enumName);

        String constants = enumDecl.getEntries().stream()
                .map(e -> e.getNameAsString())
                .collect(Collectors.joining(", "));

        chunks.add(new CodeChunk(
                "enum " + enumName + " { " + constants + " }",
                file.toString(), enumName,
                enumDecl.getBegin().map(p -> p.line).orElse(0),
                enumDecl.getEnd().map(p -> p.line).orElse(0),
                "java", "JAVA", CodeChunk.ChunkType.CODE,
                serviceName,
                Map.of("enum_constants", constants, "class_name", enumName)
        ));

        ClassNode node = new ClassNode();
        node.setQualifiedName(qualifiedName);
        node.setSimpleName(enumName);
        node.setPackageName(packageName);
        node.setFilePath(file.toString());
        node.setServiceName(serviceName);
        node.setNodeType(NodeType.ENUM);
        classNodes.add(node);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD
    // ═══════════════════════════════════════════════════════════════════════

    private void processRecord(
            RecordDeclaration recordDecl,
            Path file, String serviceName,
            Map<String, String> importMap,
            List<CodeChunk> chunks, List<Document> docChunks,
            List<ClassNode> classNodes, List<MethodNode> methodNodes,
            List<CfgNode> allCfgNodes, List<MethodDataFlow> dataFlows) {

        String packageName   = extractPackageName(recordDecl);
        String recordName    = recordDecl.getNameAsString();
        String qualifiedName = fqn(packageName, recordName);

        String components = recordDecl.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));

        chunks.add(new CodeChunk(
                "record " + recordName + "(" + components + ")",
                file.toString(), recordName,
                recordDecl.getBegin().map(p -> p.line).orElse(0),
                recordDecl.getEnd().map(p -> p.line).orElse(0),
                "java", "JAVA", CodeChunk.ChunkType.CODE,
                serviceName,
                Map.of("record_components", components, "class_name", recordName)
        ));

        ClassNode node = new ClassNode();
        node.setQualifiedName(qualifiedName);
        node.setSimpleName(recordName);
        node.setPackageName(packageName);
        node.setFilePath(file.toString());
        node.setServiceName(serviceName);
        node.setNodeType(NodeType.RECORD);
        classNodes.add(node);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ANNOTATION TYPE
    // ═══════════════════════════════════════════════════════════════════════

    private void processAnnotationType(
            AnnotationDeclaration annotDecl,
            Path file, String serviceName,
            List<ClassNode> classNodes) {

        String packageName   = extractPackageName(annotDecl);
        String qualifiedName = fqn(packageName, annotDecl.getNameAsString());

        ClassNode node = new ClassNode();
        node.setQualifiedName(qualifiedName);
        node.setSimpleName(annotDecl.getNameAsString());
        node.setPackageName(packageName);
        node.setFilePath(file.toString());
        node.setServiceName(serviceName);
        node.setNodeType(NodeType.ANNOTATION_TYPE);
        classNodes.add(node);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════════════════════════════

    private void processConstructor(
            ConstructorDeclaration ctor,
            String ownerQN, String packageName,
            Path file, String serviceName,
            List<CodeChunk> chunks, List<MethodNode> methodNodes) {

        String paramSig      = buildConstructorParamSig(ctor);
        String qualifiedName = ownerQN + ".<init>(" + paramSig + ")";

        MethodNode ctorNode = new MethodNode();
        ctorNode.setQualifiedName(qualifiedName);
        ctorNode.setSimpleName("<init>");
        ctorNode.setClassName(ctor.getNameAsString());
        ctorNode.setPackageName(packageName);
        ctorNode.setFilePath(file.toString());
        ctorNode.setServiceName(serviceName);
        ctorNode.setReturnType("void");
        ctorNode.setParameterSignature(paramSig);
        ctorNode.setStartLine(ctor.getBegin().map(p -> p.line).orElse(0));
        ctorNode.setEndLine(ctor.getEnd().map(p -> p.line).orElse(0));
        ctorNode.setContent(ctor.toString());

        int lineCount = ctorNode.getEndLine() - ctorNode.getStartLine();
        if (lineCount > 3) {
            chunks.add(new CodeChunk(
                    ctor.toString(), file.toString(),
                    ctor.getNameAsString() + " constructor",
                    ctorNode.getStartLine(), ctorNode.getEndLine(),
                    "java", "JAVA", CodeChunk.ChunkType.CODE, serviceName,
                    Map.of("class_name", ctor.getNameAsString(), "is_constructor", "true")
            ));
        }

        methodNodes.add(ctorNode);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLASS NODE BUILDER
    // ═══════════════════════════════════════════════════════════════════════

    private ClassNode buildClassNode(
            ClassOrInterfaceDeclaration classDecl,
            String qualifiedName, String packageName, String className,
            Path file, String serviceName,
            Map<String, String> importMap,
            DocumentationExtractor.ClassDocumentation classDoc) {

        ClassNode node = new ClassNode();
        node.setQualifiedName(qualifiedName);
        node.setSimpleName(className);
        node.setPackageName(packageName);
        node.setFilePath(file.toString());
        node.setServiceName(serviceName);
        node.setAbstract(classDecl.isAbstract());
        node.setPublic(classDecl.isPublic());
        node.setInterface(false);
        node.setNodeType(NodeType.CLASS);
        node.setAnnotations(extractAnnotationNames(classDecl));
        node.setSpringStereotype(detectSpringStereotype(classDecl));

        if (classDoc != null && !classDoc.getDescription().isBlank()) {
            node.setJavadocSummary(classDoc.getDescription());
        }

        // INHERITS
        classDecl.getExtendedTypes().stream().findFirst().ifPresent(ext -> {
            ClassNode superNode = new ClassNode();
            superNode.setQualifiedName(resolveType(ext.getNameAsString(), packageName, importMap));
            superNode.setSimpleName(ext.getNameAsString());
            node.setSuperClass(superNode);
        });

        // IMPLEMENTS
        classDecl.getImplementedTypes().forEach(iface -> {
            ClassNode ifaceNode = new ClassNode();
            ifaceNode.setQualifiedName(resolveType(iface.getNameAsString(), packageName, importMap));
            ifaceNode.setSimpleName(iface.getNameAsString());
            ifaceNode.setInterface(true);
            ifaceNode.setNodeType(NodeType.INTERFACE);
            node.getImplementedInterfaces().add(ifaceNode);
        });

        // DEPENDS_ON (from field declarations)
        classDecl.getFields().forEach(field -> {
            String fieldType     = field.getVariables().get(0).getTypeAsString();
            String resolvedType  = resolveType(fieldType, packageName, importMap);
            if (isProjectType(resolvedType)) {
                ClassNode dep = new ClassNode();
                dep.setQualifiedName(resolvedType);
                dep.setSimpleName(fieldType);
                node.getDependencies().add(dep);
            }
        });

        return node;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // METHOD CALL EXTRACTION (CALLS relationship)
    // ═══════════════════════════════════════════════════════════════════════

    private void extractMethodCalls(
            MethodDeclaration method,
            MethodNode callerNode,
            String ownerQN, String packageName,
            Map<String, String> importMap) {

        method.findAll(MethodCallExpr.class).forEach(callExpr -> {
            String calledMethod = callExpr.getNameAsString();
            String calleeQN     = resolveCalleeQN(callExpr, calledMethod,
                    ownerQN, packageName, importMap);

            if (calleeQN != null) {
                MethodNode calleeNode = new MethodNode();
                calleeNode.setQualifiedName(calleeQN);
                calleeNode.setSimpleName(calledMethod);

                MethodCallRelationship rel = new MethodCallRelationship();
                rel.setCallee(calleeNode);
                rel.setCallType("DIRECT");
                callExpr.getBegin().ifPresent(pos -> rel.setCallLine(pos.line));
                callerNode.getCalls().add(rel);
            }
        });
    }

    private String resolveCalleeQN(MethodCallExpr callExpr, String methodName,
                                   String ownerQN, String packageName,
                                   Map<String, String> importMap) {
        Optional<Expression> scope = callExpr.getScope();
        if (scope.isEmpty()) return ownerQN + "." + methodName + "(?)";

        String scopeStr = scope.get().toString();
        if ("this".equals(scopeStr)) return ownerQN + "." + methodName + "(?)";

        if (Character.isUpperCase(scopeStr.charAt(0))) {
            String resolved = resolveType(scopeStr, packageName, importMap);
            return resolved + "." + methodName + "(?)";
        }

        return packageName + "." + scopeStr + "." + methodName + "(?)";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SPRING ENRICHMENT
    // ═══════════════════════════════════════════════════════════════════════

    private void enrichWithSpringAnnotations(
            MethodDeclaration method,
            MethodNode methodNode,
            Map<String, String> extra,
            String classLevelMapping) {

        method.getAnnotations().forEach(annotation -> {
            String name = annotation.getNameAsString();

            if (HTTP_ANNOTATIONS.contains(name)) {
                String httpMethod = deriveHttpMethod(name);
                String path       = extractAnnotationValue(annotation);
                String fullPath   = classLevelMapping.isEmpty() ? path : classLevelMapping + path;

                methodNode.setEndpoint(true);
                methodNode.setHttpMethod(httpMethod);
                methodNode.setEndpointPath(fullPath);
                extra.put("http_method", httpMethod);
                extra.put("endpoint", fullPath);
                extra.put("is_endpoint", "true");
            }
            if ("Transactional".equals(name)) {
                methodNode.setTransactional(true);
                extra.put("is_transactional", "true");
            }
            if ("Scheduled".equals(name)) {
                methodNode.setScheduled(true);
                String schedule = extractAnnotationValue(annotation);
                methodNode.setScheduleExpression(schedule);
                extra.put("is_scheduled", "true");
                extra.put("schedule", schedule);
            }
            if ("EventListener".equals(name) || "KafkaListener".equals(name)) {
                methodNode.setEventHandler(true);
                extra.put("is_event_handler", "true");
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private Map<String, String> buildImportMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        cu.getImports().forEach(imp -> {
            String name = imp.getNameAsString();
            String simple = name.contains(".")
                    ? name.substring(name.lastIndexOf('.') + 1) : name;
            map.put(simple, name);
        });
        return map;
    }

    private String extractPackageName(TypeDeclaration<?> td) {
        return td.findAncestor(CompilationUnit.class)
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString())
                .orElse("");
    }

    private String extractPackageName(RecordDeclaration rd) {
        return rd.findAncestor(CompilationUnit.class)
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString())
                .orElse("");
    }

    private String fqn(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private List<String> extractAnnotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());
    }

    private String detectSpringStereotype(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .filter(SPRING_STEREOTYPES::containsKey)
                .map(SPRING_STEREOTYPES::get)
                .findFirst().orElse(null);
    }

    private String extractClassLevelMapping(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotationByName("RequestMapping")
                .map(this::extractAnnotationValue).orElse("");
    }

    private String buildParamSignature(MethodDeclaration method) {
        return method.getParameters().stream()
                .map(p -> p.getTypeAsString())
                .collect(Collectors.joining(","));
    }

    private String buildConstructorParamSig(ConstructorDeclaration ctor) {
        return ctor.getParameters().stream()
                .map(p -> p.getTypeAsString())
                .collect(Collectors.joining(","));
    }

    private String resolveType(String typeName, String packageName,
                               Map<String, String> importMap) {
        if (typeName == null || typeName.contains(".")) return typeName;
        if (importMap.containsKey(typeName)) return importMap.get(typeName);
        return packageName.isEmpty() ? typeName : packageName + "." + typeName;
    }

    private boolean isProjectType(String qn) {
        return qn != null && qn.contains(".")
                && !qn.startsWith("java.") && !qn.startsWith("javax.")
                && !qn.startsWith("org.springframework.")
                && !qn.startsWith("lombok.");
    }

    private String deriveHttpMethod(String name) {
        return switch (name) {
            case "GetMapping" -> "GET"; case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT"; case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH"; default -> "ANY";
        };
    }

    private String extractAnnotationValue(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return annotation.asSingleMemberAnnotationExpr()
                    .getMemberValue().toString().replace("\"", "");
        }
        if (annotation.isNormalAnnotationExpr()) {
            return annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value")
                            || p.getNameAsString().equals("path"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .orElse("");
        }
        return "";
    }

    private boolean isTestFile(Path file) {
        String p = file.toString();
        return p.contains("/test/") || p.endsWith("Test.java")
                || p.endsWith("Tests.java") || p.endsWith("Spec.java");
    }
}