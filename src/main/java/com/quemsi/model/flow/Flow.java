package com.quemsi.model.flow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.DateUtils;
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
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class Flow {
	@Autowired
	private ApiClient apiClient;
	@Autowired
	private DateUtils dateUtils;
	private Step first;
	private Long id;
	private String name;
	private String title;
	private boolean active;
	private boolean back;
	private DataGroup data;
	private String timerName;
	private int numberOfSteps;
	
	@JsonIgnore
	protected ReentrantLock lock = new ReentrantLock();
	
	public void initialize() {
		first.init(this);
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
				apiClient.saveFlowExecution(execution);
				if(!this.back){
					if(!fc.getTags().containsKey("date")){
						fc.getTags().put("date", dateUtils.getDateString(LocalDateTime.now()));
					}
					if(!fc.getTags().containsKey("time")){
						fc.getTags().put("time", dateUtils.getTimeString(LocalDateTime.now()));
					}
					fc.getDataVersion().setTags(fc.getTags().entrySet().stream().map(e -> Tag.builder().name(e.getKey()).val(e.getValue()).build()).toList());
				}
				if(!this.isReady()) {
					log.info("{} flow initialization is not completed yet", this.getName());
					fc.getExecution().setStatus(FlowExecutionStatus.SKIPPED);
				} else {
					try {
						first.execute(fc);
						fc.getExecution().setStatus(FlowExecutionStatus.SUCCESS);
					}catch(BaseRuntimeException bre) {
						fc.logError("execution error", bre);
					}catch(Exception e) {
						fc.logError("general error", e);
					}
				}
				fc.getExecution().setFinishedAt(LocalDateTime.now());
				fc.getExecution().setVersion(fc.getDataVersion());
				return fc.getExecution();
			}else {
				log.info("{} flow is already running", this.getName());
				return null;
			}
		}finally{
			if(lock != null && lock.isLocked()){
				lock.unlock();
			}
		}
	}

	public FlowExecution execute(Long versionId, Map<String, String> tags, List<DataFile> files, Long executionId) {
		FlowContext fc = new FlowContext(this, executionId);
		fc.setTags(tags);
		fc.setDataVersion(DataVersion.builder().id(versionId).files(files).data(NamedEntityReference.builder().id(data.getId()).name(data.getName()).build()).build());
		return execute(fc);
	}

	public void setSteps(List<Step> steps) {
		numberOfSteps = steps.size();
		Step pre = null;
		for (int i = 0; i < steps.size(); i++) {
			Step s = steps.get(i);
			if(s instanceof AbstractStep) {
				AbstractStep sf = (AbstractStep)s;
				sf.setFlow(this);
			}
			if (pre != null) {
				pre.setNextStep(s);
			} else if (i == 0) {
				this.setFirst(s);
			}
			pre = s;
		}
	}

	public boolean isReady() {
		return first.isReady();
	}

	public FlowDetail toDetail() {
		List<Map<String, Object>> steps = new ArrayList<>();
		first.fillDetails(steps);
		return FlowDetail.builder().id(this.id).name(name).title(title).data(data).active(active).back(back).steps(steps).build();
	}	
}
