package com.servicelens.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Central Spring bean configuration for ServiceLens infrastructure.
 *
 * <p>This class wires together the three pieces of infrastructure that require
 * explicit construction because they depend on runtime configuration values:
 * <ol>
 *   <li><b>Ollama API client</b> – a single shared HTTP client pointed at the
 *       local Ollama server.  Shared between the chat model and the embedding
 *       model to avoid redundant connections.</li>
 *   <li><b>Chat client</b> – wraps {@link OllamaChatModel} configured for
 *       deterministic output (temperature = 0.0) for use by the cross-encoder
 *       reranker.  The {@link ChatClient} fluent API is preferred over the raw
 *       model because it provides a clean, builder-style call interface.</li>
 *   <li><b>pgvector store</b> – connects Spring AI's vector store abstraction
 *       to PostgreSQL with the pgvector extension.  Uses HNSW indexing and
 *       cosine distance to match the dimensionality of {@code nomic-embed-text}
 *       (768 dimensions).</li>
 * </ol>
 *
 * <p>All other beans ({@code @Component}, {@code @Service}, {@code @Repository})
 * are auto-discovered by Spring's component scan and do not need explicit
 * declaration here.
 *
 * <p>Configuration properties are read from {@code application.yml} via
 * {@link Value} injection so that the infrastructure can be reconfigured
 * without touching Java source code.
 */
@Configuration
public class ServiceLensConfig {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.chat.model:phi3}")
    private String chatModel;

    @Value("${spring.ai.ollama.embedding.model:nomic-embed-text}")
    private String embeddingModel;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:768}")
    private int vectorDimensions;

    @Value("${servicelens.groq.api-key:}")
    private String groqApiKey;

    @Value("${servicelens.groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    /**
     * Shared Ollama HTTP API client.
     *
     * <p>Pointed at the local Ollama server whose base URL is configured in
     * {@code spring.ai.ollama.base-url}.  Shared by both the chat model and
     * the embedding model so that only one connection pool is maintained.
     *
     * @return a configured {@link OllamaApi} instance
     */
    @Bean
    public OllamaApi ollamaApi() {
        return new OllamaApi(ollamaBaseUrl);
    }

    /**
     * Ollama chat model used by the cross-encoder reranker.
     *
     * <p>Configured with temperature {@code 0.0} for deterministic scoring
     * output and a small context window of {@code 2048} tokens because
     * reranker prompts are intentionally short.
     *
     * @param ollamaApi the shared Ollama API client
     * @return a configured {@link OllamaChatModel}
     */
    @Bean
    public OllamaChatModel ollamaChatModel(OllamaApi ollamaApi) {
        return new OllamaChatModel(ollamaApi,
                OllamaOptions.create()
                        .withModel(chatModel)
                        .withTemperature(0.0)
                        .withNumCtx(2048));
    }

    /**
     * Ollama-backed {@link ChatClient} used by the cross-encoder reranker.
     *
     * <p>This bean is retained for the reranker which needs fast, local,
     * deterministic scoring — not full LLM synthesis.
     *
     * @param ollamaChatModel the configured Ollama chat model
     * @return a ready-to-use {@link ChatClient}
     */
    @Bean
    @Primary
    @Qualifier("ollamaChatClient")
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    /**
     * Groq-backed {@link ChatClient} for answer synthesis.
     *
     * <p>Uses Groq's OpenAI-compatible API with LLaMA 3.3 70B for
     * high-quality, low-latency synthesis (~500-800 tokens/sec).
     * Ollama embeddings remain local; only the chat/synthesis step
     * is offloaded to Groq's cloud inference.
     *
     * @return a Groq-backed {@link ChatClient}
     */
    @Bean
    @Qualifier("groqChatClient")
    public ChatClient groqChatClient() {
        OpenAiApi groqApi = new OpenAiApi("https://api.groq.com/openai", groqApiKey);
        OpenAiChatModel groqChatModel = new OpenAiChatModel(groqApi,
                OpenAiChatOptions.builder()
                        .withModel(groqModel)
                        .withTemperature(0.5)
                        .build());
        return ChatClient.builder(groqChatModel).build();
    }

    /**
     * Ollama embedding model used internally by the pgvector store.
     *
     * <p>Uses {@code nomic-embed-text} which produces 768-dimensional vectors,
     * matching the {@code dimensions} setting in the vector store configuration.
     *
     * @param ollamaApi the shared Ollama API client
     * @return a configured {@link OllamaEmbeddingModel}
     */
    @Bean
    @Primary
    public OllamaEmbeddingModel ollamaEmbeddingModel(OllamaApi ollamaApi) {
        return new OllamaEmbeddingModel(ollamaApi,
                OllamaOptions.create().withModel(embeddingModel));
    }

    /**
     * pgvector-backed vector store for semantic code search.
     *
     * <p>Configuration:
     * <ul>
     *   <li>Dimensions: {@code 768} – matches {@code nomic-embed-text} output size.</li>
     *   <li>Distance: {@code COSINE_DISTANCE} – appropriate for normalised text embeddings.</li>
     *   <li>Index: {@code HNSW} – approximate nearest-neighbour, fast at query time.</li>
     *   <li>Schema init: {@code true} – creates the {@code vector_store} table and
     *       index on first startup if absent.</li>
     * </ul>
     *
     * @param jdbcTemplate     JDBC connection to PostgreSQL (auto-configured by Spring Boot)
     * @param embeddingModel   the embedding model that converts text to vectors
     * @return a configured {@link VectorStore}
     */
    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate,
                                   OllamaEmbeddingModel embeddingModel) {
        return new PgVectorStore.Builder(jdbcTemplate, embeddingModel)
                .withDimensions(vectorDimensions)
                .withDistanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .withIndexType(PgVectorStore.PgIndexType.HNSW)
                .withInitializeSchema(true)
                .build();
    }

    /**
     * Jackson {@link ObjectMapper} used to serialise DFG summary maps
     * before storing them as JSON in Neo4j node properties.
     *
     * @return a default-configured {@link ObjectMapper}
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
