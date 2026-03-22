package com.servicelens.graph.repository;

import com.servicelens.graph.domain.ClassNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data Neo4j repository for {@link ClassNode} persistence and graph queries.
 *
 * <p>Provides standard CRUD operations inherited from {@link Neo4jRepository} and
 * a set of custom Cypher queries for structural analysis of the class graph. All
 * custom queries are scoped to the current service where relevant to avoid
 * cross-service result contamination.</p>
 *
 * <p>Query categories supported by this repository:</p>
 * <ul>
 *   <li>Inheritance hierarchy — find subclasses and ancestor chains</li>
 *   <li>Interface implementation — find concrete implementors</li>
 *   <li>Dependency analysis — find classes that depend on a given class</li>
 *   <li>Spring stereotype queries — find beans, controllers</li>
 *   <li>File-level deletion — remove all class nodes for a given file path</li>
 * </ul>
 */
public interface ClassNodeRepository extends Neo4jRepository<ClassNode, String> {

    /**
     * Find all class nodes belonging to a specific service.
     *
     * @param serviceName the logical service name to filter by
     * @return list of all {@link ClassNode} instances in the given service
     */
    List<ClassNode> findByServiceName(String serviceName);

    /**
     * Find a class node by its fully-qualified class name.
     *
     * @param qualifiedName the fully-qualified name (e.g. {@code com.example.PaymentService})
     * @return an {@link Optional} containing the class if found, or empty if not
     */
    Optional<ClassNode> findByQualifiedName(String qualifiedName);

    /**
     * Find all classes that directly inherit from the specified parent class.
     *
     * <p>Traverses {@code INHERITS} relationships in the forward direction.
     * Uses {@code CONTAINS} substring matching on the parent class name to
     * tolerate partially-qualified names.</p>
     *
     * @param className the qualified or partial name of the parent class
     * @return list of {@link ClassNode} instances that extend the specified class
     */
    @Query("MATCH (child:Class)-[:INHERITS]->(parent:Class) " +
            "WHERE parent.qualifiedName CONTAINS $className " +
            "RETURN child")
    List<ClassNode> findSubclasses(String className);

    /**
     * Find all ancestor classes in the inheritance chain above the specified class.
     *
     * <p>Traverses {@code INHERITS} relationships transitively up to 10 hops,
     * returning all ancestor nodes found along the way.</p>
     *
     * @param qualifiedName the fully-qualified name of the starting class
     * @return list of {@link ClassNode} ancestor instances in no guaranteed order
     */
    @Query("MATCH path = (cls:Class {qualifiedName: $qualifiedName})" +
            "-[:INHERITS*1..10]->(ancestor:Class) " +
            "RETURN ancestor")
    List<ClassNode> findAllAncestors(String qualifiedName);

    /**
     * Find all classes that implement the specified interface.
     *
     * <p>Traverses {@code IMPLEMENTS} relationships. Uses substring matching on
     * the interface name to tolerate partially-qualified names.</p>
     *
     * @param interfaceName the qualified or partial name of the interface
     * @return list of {@link ClassNode} instances that implement the interface
     */
    @Query("MATCH (cls:Class)-[:IMPLEMENTS]->(iface:Class) " +
            "WHERE iface.qualifiedName CONTAINS $interfaceName " +
            "RETURN cls")
    List<ClassNode> findImplementors(String interfaceName);

    /**
     * Find all classes within a service that directly depend on the specified class.
     *
     * <p>Traverses {@code DEPENDS_ON} relationships in reverse to find classes that
     * inject or reference the target. Used for change-impact analysis.</p>
     *
     * @param className   the qualified or partial name of the target class
     * @param serviceName the service scope to restrict results to
     * @return list of {@link ClassNode} instances that depend on the specified class
     */
    @Query("MATCH (dependent:Class)-[:DEPENDS_ON]->(target:Class) " +
            "WHERE target.qualifiedName CONTAINS $className " +
            "AND dependent.serviceName = $serviceName " +
            "RETURN dependent")
    List<ClassNode> findDependents(String className, String serviceName);

    /**
     * Find all classes reachable via transitive {@code DEPENDS_ON} relationships
     * from the specified class, up to 3 hops.
     *
     * @param qualifiedName the fully-qualified name of the starting class
     * @return list of transitively reachable dependency {@link ClassNode} instances
     */
    @Query("MATCH path = (cls:Class {qualifiedName: $qualifiedName})" +
            "-[:DEPENDS_ON*1..3]->(dep:Class) " +
            "RETURN dep")
    List<ClassNode> findTransitiveDependencies(String qualifiedName);

    /**
     * Find all Spring-managed bean classes within a service.
     *
     * <p>Returns any class that has a non-null {@code springStereotype} property,
     * which is set during parsing when a class carries {@code @Component},
     * {@code @Service}, {@code @Repository}, {@code @Controller}, etc.</p>
     *
     * @param serviceName the service to query
     * @return list of {@link ClassNode} instances that are Spring beans
     */
    @Query("MATCH (c:Class {serviceName: $serviceName}) " +
            "WHERE c.springStereotype IS NOT NULL " +
            "RETURN c")
    List<ClassNode> findSpringBeans(String serviceName);

    /**
     * Find all Spring MVC controller classes within a service.
     *
     * <p>Returns classes annotated with either {@code @Controller} or
     * {@code @RestController}.</p>
     *
     * @param serviceName the service to query
     * @return list of {@link ClassNode} instances that are Spring MVC controllers
     */
    @Query("MATCH (c:Class {serviceName: $serviceName, springStereotype: '@Controller'}) " +
            "RETURN c " +
            "UNION " +
            "MATCH (c:Class {serviceName: $serviceName, springStereotype: '@RestController'}) " +
            "RETURN c")
    List<ClassNode> findControllers(String serviceName);

    /**
     * Delete all class nodes whose {@code filePath} property matches the given path.
     *
     * <p>Uses {@code DETACH DELETE} to also remove all relationships connected to
     * the deleted nodes. Used during incremental ingestion to clean up stale data
     * when a source file is modified or deleted.</p>
     *
     * @param filePath the absolute path of the source file whose class nodes should be removed
     */
    @Query("MATCH (c:Class {filePath: $filePath}) DETACH DELETE c")
    void deleteByFilePath(String filePath);
}
