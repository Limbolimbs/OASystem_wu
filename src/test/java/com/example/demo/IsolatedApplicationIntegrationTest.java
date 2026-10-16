package com.example.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "OA_ACCEPTANCE_TEST", matches = "true")
class IsolatedApplicationIntegrationTest extends com.example.demo.acceptance.IsolatedIntegrationSupport {

	@Test
	void contextLoads() {
	}

}
