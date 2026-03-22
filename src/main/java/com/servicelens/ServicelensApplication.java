package com.servicelens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;

/**
 * Entry point for the ServiceLens Spring Boot application.
 *
 * <p>ServiceLens is an AI-powered code intelligence platform that ingests
 * microservice source repositories and makes them queryable through a combination
 * of semantic vector search (pgvector) and structural knowledge-graph traversal
 * (Neo4j). It supports incremental re-ingestion, intent-based retrieval routing,
 * and multi-layer hybrid retrieval for grounding LLM-based agents with accurate
 * codebase context.</p>
 *
 * <p>All Spring components are discovered automatically via
 * {@link SpringBootApplication} component scanning from the
 * {@code com.servicelens} base package.</p>
 */
@SpringBootApplication(exclude = OpenAiAutoConfiguration.class)
public class ServicelensApplication {

	/**
	 * Bootstrap the ServiceLens application.
	 *
	 * @param args command-line arguments forwarded to {@link SpringApplication#run}
	 */
	public static void main(String[] args) {
		SpringApplication.run(ServicelensApplication.class, args);
	}

}
