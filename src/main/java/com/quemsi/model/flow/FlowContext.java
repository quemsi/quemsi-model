package com.quemsi.model.flow;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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
			StringWriter sw = step.logWriter();
			e.printStackTrace(new PrintWriter(sw));
			step.setLogs(sw.toString());
		}
	}
	public void logError(String tag, Throwable e) {
		log.error(tag, e);
		execution.setStatus(FlowExecutionStatus.FAILED);
		StringWriter sw = execution.logWriter();
		e.printStackTrace(new PrintWriter(sw));
		execution.setLogs(sw.toString());
	}
}
