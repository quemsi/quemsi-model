package com.quemsi.model.flow.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.dto.RedisClearMode;
import com.quemsi.model.dto.RedisConnectionMode;
import com.quemsi.model.flow.redis.RedisConnectionSupport.RedisSession;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
public class RedisReplicaDetectionIntegrationTest {

	static boolean dockerAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		} catch (Throwable t) {
			return false;
		}
	}

	static Network network = Network.newNetwork();

	@Container
	@SuppressWarnings("resource")
	static GenericContainer<?> master = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379)
			.withNetwork(network)
			.withNetworkAliases("redis-master");

	@Container
	@SuppressWarnings("resource")
	static GenericContainer<?> replica = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379)
			.withNetwork(network)
			.dependsOn(master)
			.withCommand("redis-server", "--replicaof", "redis-master", "6379");

	@Test
	public void connectingToReplicaFailsFast() {
		ClearRedisConfig config = configFor(replica);

		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
				() -> RedisConnectionSupport.open(config, RedisConnectionSupport.resolveCredentials(config)));
		assertThat(ex.getMessageId(), equalTo("redis-target-is-replica"));
	}

	@Test
	public void clearAgainstReplicaFailsBeforeDeleting() {
		ClearRedisConfig config = configFor(replica);
		config.setClearMode(RedisClearMode.PATTERNS);
		config.setPatterns(List.of("*"));

		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
				() -> new RedisClearService().clear(config, null));
		assertThat(ex.getMessageId(), equalTo("redis-target-is-replica"));
	}

	@Test
	public void connectingToMasterSucceeds() {
		ClearRedisConfig config = configFor(master);
		try (RedisSession session = RedisConnectionSupport.open(config,
				RedisConnectionSupport.resolveCredentials(config))) {
			assertThat(session.jedis().ping(), equalTo("PONG"));
		}
	}

	private ClearRedisConfig configFor(GenericContainer<?> container) {
		return ClearRedisConfig.builder()
				.mode(RedisConnectionMode.STANDALONE)
				.host(container.getHost())
				.port(container.getMappedPort(6379))
				.database(0)
				.connectTimeoutMs(3000)
				.readTimeoutMs(5000)
				.build();
	}
}
