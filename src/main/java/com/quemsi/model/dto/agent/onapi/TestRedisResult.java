package com.quemsi.model.dto.agent.onapi;

import com.quemsi.model.dto.agent.AgentCommandSync;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class TestRedisResult extends AgentCommandSync {
	private boolean success;
	private String message;
	private int errorCode;
	private String errorMessage;
	private String discoveredMaster;

	@Builder
	public TestRedisResult(Long agentId, Long correlationId, long timeoutMilis, boolean success, int errorCode,
			String message, String errorMessage, String discoveredMaster) {
		super(TestRedisResult.class.getSimpleName(), agentId, correlationId, timeoutMilis);
		this.success = success;
		this.errorCode = errorCode;
		this.message = message;
		this.errorMessage = errorMessage;
		this.discoveredMaster = discoveredMaster;
	}
}
