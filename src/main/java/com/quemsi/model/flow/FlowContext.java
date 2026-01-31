package com.quemsi.model.flow;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DataVersion;
import com.quemsi.model.dto.FlowExecution;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.flow.process.DbModelProcessor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class FlowContext {
	private boolean deleteAfterwards;
	private FlowExecution execution;
	private DataVersion dataVersion;
	private List<DataPackage> dataPackages;
	private Flow flow;
	private Map<String, String> tags;
	private List<DbModelProcessor> dbModelProcessors;
	private FlowExecutionStep currentStep;
	
	@FunctionalInterface
	public interface LogWriter {
		void log(Long agentId, Long flowExecutionId, Long flowExecutionStepId, LogMessage message);
	}
	
	private LogWriter logWriter;
	
	public FlowContext(Flow flow, Long flowExecutionId) {
		execution = new FlowExecution();
		execution.setId(flowExecutionId);
		execution.setActive(true);
		execution.setFlowId(flow.getId());
		execution.setFlowName(flow.getName());
		execution.setNumberOfSteps(flow.numberOfSteps());
		execution.setStatus(FlowExecutionStatus.SCHEDULED);
		this.tags = new HashMap<>();
		this.flow = flow;
		dataPackages = new LinkedList<>();
		dbModelProcessors = new LinkedList<>();
	}

	public void setDataVersion(DataVersion dataVersion){
		this.dataVersion = dataVersion;
		execution.setVersion(dataVersion);
	}
	
	public boolean inError() {
		return FlowExecutionStatus.FAILED.equals(execution.getStatus());
	}
	public Long executionVersion(){
		return this.dataVersion.getId();
	}
	public void logError(FlowExecutionStep step, String tag, Throwable e) {
		log.error(tag, e);
		if(step != null){
			step.setStatus(FlowExecutionStatus.FAILED);
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			if (logWriter != null && execution != null && execution.getId() != null && step.getId() != null) {
				logWriter.log(null, execution.getId(), step.getId(), LogMessage.error("{}: {}", tag, e.getMessage()));
				logWriter.log(null, execution.getId(), step.getId(), LogMessage.error(sw.toString()));
			}
		}
	}
	public void logError(String tag, Throwable e) {
		log.error(tag, e);
		execution.setStatus(FlowExecutionStatus.FAILED);
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		if (logWriter != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.error("{}: {}", tag, e.getMessage()));
			logWriter.log(null, execution.getId(), null, LogMessage.error(sw.toString()));
		}
	}
	
	public void logInfo(String message) {
		if (logWriter != null && execution != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.info(message));
		}
	}
	
	public void logWarn(String message) {
		if (logWriter != null && execution != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.warn(message));
		}
	}
	
	public void logError(String message) {
		if (logWriter != null && execution != null && execution.getId() != null) {
			logWriter.log(null, execution.getId(), null, LogMessage.error(message));
		}
	}
	
	public void logStepInfo(FlowExecutionStep step, LogMessage message) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.info(message));
		}
	}
	
	public void logStepWarn(FlowExecutionStep step, String message) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.warn(message));
		}
	}
	
	public void logStepError(FlowExecutionStep step, String message) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.error(message));
		}
	}public void logStepError(FlowExecutionStep step, String tag, Throwable e) {
		if (logWriter != null && execution != null && execution.getId() != null && step != null && step.getId() != null) {
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.error("{}: {}", tag, e.getMessage()));
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			logWriter.log(null, execution.getId(), step.getId(), LogMessage.error(sw.toString()));
		}
	}
}
