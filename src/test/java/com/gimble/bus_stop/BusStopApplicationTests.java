package com.gimble.bus_stop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole app against Postgres 17 in a container: every Flyway migration applies cleanly,
 * matches the JPA entities (ddl-auto=validate), and the demo data seeds.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "test"})
class BusStopApplicationTests {

	@Test
	void contextLoads() {
	}

}
