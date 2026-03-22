package com.servicelens;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires live infrastructure: Neo4j, PostgreSQL, and Ollama must be running")
class ServicelensApplicationTests {

	@Test
	void contextLoads() {
	}

}
