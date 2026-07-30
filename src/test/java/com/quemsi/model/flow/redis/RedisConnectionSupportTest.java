package com.quemsi.model.flow.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.ClearRedisConfig;
import com.quemsi.model.dto.RedisClearMode;
import com.quemsi.model.dto.RedisConnectionMode;

public class RedisConnectionSupportTest {

	@Test
	public void validateStandaloneRequiresHostAndPort() {
		ClearRedisConfig config = ClearRedisConfig.builder()
				.mode(RedisConnectionMode.STANDALONE)
				.build();
		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
				() -> RedisConnectionSupport.validateConnectionConfig(config));
		assertThat(ex.getMessageId(), equalTo("redis-host-needed"));
	}

	@Test
	public void validateSentinelRequiresMasterAndSentinels() {
		ClearRedisConfig config = ClearRedisConfig.builder()
				.mode(RedisConnectionMode.SENTINEL)
				.sentinels(List.of("127.0.0.1:26379"))
				.build();
		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
				() -> RedisConnectionSupport.validateConnectionConfig(config));
		assertThat(ex.getMessageId(), equalTo("redis-master-name-needed"));
	}

	@Test
	public void validatePatternsRequiresNonEmptyPattern() {
		ClearRedisConfig config = ClearRedisConfig.builder()
				.mode(RedisConnectionMode.STANDALONE)
				.host("localhost")
				.port(6379)
				.clearMode(RedisClearMode.PATTERNS)
				.patterns(List.of())
				.build();
		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
				() -> RedisConnectionSupport.validateClearConfig(config));
		assertThat(ex.getMessageId(), equalTo("redis-pattern-required"));
	}

	@Test
	public void starPatternIsAccepted() {
		ClearRedisConfig config = ClearRedisConfig.builder()
				.mode(RedisConnectionMode.STANDALONE)
				.host("localhost")
				.port(6379)
				.clearMode(RedisClearMode.PATTERNS)
				.patterns(List.of("*"))
				.build();
		RedisConnectionSupport.validateClearConfig(config);
	}

	@Test
	public void isFailoverCandidateDetectsReadonly() {
		assertThat(RedisConnectionSupport.isFailoverCandidate(
				new RuntimeException("READONLY You can't write against a read only replica")), equalTo(true));
	}
}
