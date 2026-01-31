package com.quemsi.model.flow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.DateUtils;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.api.ApiClient;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.dto.DataGroup;
import com.quemsi.model.dto.DataVersion;
import com.quemsi.model.dto.FlowDetail;
import com.quemsi.model.dto.FlowExecution;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.dto.NamedEntityReference;
import com.quemsi.model.dto.Tag;
import com.quemsi.model.exception.FlowExecutionAbortedException;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class Flow {
	@Autowired
	private ApiClient apiClient;
	@Autowired
	private DateUtils dateUtils;
	private Long id;
	private String name;
	private String title;
	private boolean active;
	private boolean back;
	private DataGroup data;
	private String timerName;
	private List<Step> steps;
	
	@JsonIgnore
	protected ReentrantLock lock = new ReentrantLock();
	
	@JsonIgnore
	private FlowContext.LogWriter logWriter;
	
	public void initialize() {
		steps.forEach(s -> s.init(this));
	}
	
	public FlowExecutionStep sendStepStarted(Long flowExecutionId, String type, Integer ord, LocalDateTime started){
		FlowExecutionStep step = FlowExecutionStep.builder()
			.active(true)
			.flowExecutionId(flowExecutionId)
			.status(FlowExecutionStatus.RUNNING)
			.startedAt(started)
			.ord(ord)
			.type(type)
			.build()
		;
		return apiClient.saveFlowExecutionStep(step);
	}

	public void sendStepFinished(FlowExecutionStep step, FlowExecutionStatus status){
		step.setFinishedAt(LocalDateTime.now());
		step.setStatus(status);
		apiClient.saveFlowExecutionStep(step);
	}

	public FlowExecution execute(FlowContext fc){
		try{
			if(lock.tryLock()) {
				FlowExecution execution = fc.getExecution();
				execution.setStartedAt(LocalDateTime.now());
				execution.setStatus(FlowExecutionStatus.RUNNING);
				if(!this.back){
					if(!fc.getTags().containsKey("date")){
						fc.getTags().put("date", dateUtils.getDateString(LocalDateTime.now()));
					}
					if(!fc.getTags().containsKey("time")){
						fc.getTags().put("time", dateUtils.getTimeString(LocalDateTime.now()));
					}
					fc.getTags().put("flow", this.name);
					fc.getDataVersion().setTags(fc.getTags().entrySet().stream().map(e -> Tag.builder().name(e.getKey()).val(e.getValue()).build()).toList());
				}
				apiClient.saveFlowExecution(execution);
				fc.logInfo("flow execution started");
				if(!this.isReady()) {
					log.info("{} flow initialization is not completed yet", this.getName());
					fc.logError("failed-to-execute-flow", Exceptions.server("flow initialization is not completed yet").get());
					fc.getExecution().setStatus(FlowExecutionStatus.SKIPPED);
					fc.logWarn("flow execution skipped");
				} else {
					try {
						for(Step s : steps) {
							FlowExecutionStep fes = null;
							try {
								fes = sendStepStarted(fc.getExecution().getId(), s.getType(), s.getOrd() , LocalDateTime.now());
								fc.setCurrentStep(fes);
								fc.logStepInfo(fes, LogMessage.info("step started"));
								s.execute(fc);
								fc.logStepInfo(fes, LogMessage.info("step finished"));
								sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
							}catch(Exception bre) {
								fc.logStepError(fes, "step failed", bre);
								sendStepFinished(fes, FlowExecutionStatus.FAILED);
								throw new FlowExecutionAbortedException("step failed", bre);
							}
						}
						fc.getExecution().setStatus(FlowExecutionStatus.SUCCESS);
						fc.logInfo("flow execution succeeded");
					}
					catch(FlowExecutionAbortedException bre) {
						fc.getExecution().setStatus(FlowExecutionStatus.FAILED);
					}catch(BaseRuntimeException bre) {
						fc.logError("execution error", bre);
						fc.logError("flow execution failed");
					}catch(Exception e) {
						fc.logError("general error", e);	
						fc.logError("flow execution failed");
					}
				}
				fc.getExecution().setFinishedAt(LocalDateTime.now());
				fc.getExecution().setVersion(fc.getDataVersion());
				fc.logInfo("flow execution finished");
				return fc.getExecution();
			}else {
				log.info("{} flow is already running", this.getName());
				fc.logWarn("flow is already running");
				return null;
			}
		}finally{
			if(lock != null && lock.isLocked()){
				lock.unlock();
			}
		}
	}

	public int numberOfSteps() {
		return steps==null?0:steps.size();
	}

	public FlowExecution execute(Long versionId, Map<String, String> tags, List<DataFile> files, Long executionId) {
		FlowContext fc = new FlowContext(this, executionId);
		fc.setTags(tags);
		fc.setDataVersion(DataVersion.builder().id(versionId).files(files).data(NamedEntityReference.builder().id(data.getId()).name(data.getName()).build()).build());
		if (logWriter != null) {
			fc.setLogWriter(logWriter);
		}
		return execute(fc);
	}

	public void setSteps(List<Step> steps) {
		this.steps = steps;
		for(Step s : steps) {
			if(s instanceof AbstractStep as) {
				as.setFlow(this);
			}
		}
	}

	public boolean isReady() {
		for(Step s : steps) {
			if(!s.isReady()) {
				return false;
			}
		}
		return true;
	}

	public FlowDetail toDetail() {
		List<Map<String, Object>> stepsDetails = new ArrayList<>();
		for(Step s : steps) {
			s.fillDetails(stepsDetails);
		}
		return FlowDetail.builder().id(this.id).name(name).title(title).data(data).active(active).back(back).steps(stepsDetails).build();
	}	
}
