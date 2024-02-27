package com.biddflux.model.flow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.EnvironmentVars;
import com.biddflux.commons.util.BaseRuntimeException;
import com.biddflux.commons.util.DateUtils;
import com.biddflux.model.dto.DataGroup;
import com.biddflux.model.dto.FlowDetail;
import com.biddflux.model.dto.FlowHistoryStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class Flow {
	@JsonIgnore
	// @Autowired
	// private FlowHistoryServiceImpl flowHistoryService;
	// @JsonIgnore
	// @Autowired
	// private DataVersionServiceImpl dataVersionService;
	@Autowired
	private DateUtils dateUtils;
	private Step first;
	private Long id;
	private String name;
	private String title;
	private boolean active;
	private boolean back;
	private DataGroup data;
	
	@JsonIgnore
	protected ReentrantLock lock = new ReentrantLock();
	
	public void initialize(EnvironmentVars env) {
		first.init(this, env);
	}
	
	public void execute(FlowContext fc){
		try{
			if(lock.tryLock()) {
				if(!this.back && fc.getDataVersion() == null){
					if(!fc.getTags().containsKey("date")){
						fc.getTags().put("date", dateUtils.getDateString(LocalDateTime.now()));
					}
					if(!fc.getTags().containsKey("time")){
						fc.getTags().put("time", dateUtils.getTimeString(LocalDateTime.now()));
					}
					// fc.setDataVersion(dataVersionService.createNew(this.data, fc.getTags()));
				}
				if(!this.isReady()) {
					log.info("{} flow initialization is not completed yet", this.getName());
					fc.getFlowHistory().setStatus(FlowHistoryStatus.SKIPPED);
				} else {
					try {
						first.execute(fc);
						fc.getFlowHistory().setStatus(FlowHistoryStatus.SUCCESS);
					}catch(BaseRuntimeException bre) {
						fc.logError("execution error", bre);
					}catch(Exception e) {
						fc.logError("general error", e);
					}
				}
				fc.getFlowHistory().setFinishedAt(new Date(System.currentTimeMillis()));
				fc.getFlowHistory().setVersion(fc.getDataVersion());
				// flowHistoryService.save(fc.getFlowHistory());
			}else {
				log.info("{} flow is already running", this.getName());
			}
		}finally{
			lock.unlock();
		}
	}

	public void execute() {
		log.info("starting flow {} without parameter");
		FlowContext fc = new FlowContext(this);
		execute(fc);
	}

	public void execute(Map<String, String> tags) {
		FlowContext fc = new FlowContext(this);
		fc.setTags(tags);
		execute(fc);
	}

	public void setSteps(List<Step> steps) {
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

	public FlowRunnable getRunnable(String timerName){
		return new FlowRunnable(timerName);
	}

	public class FlowRunnable implements Runnable
	{
		@Getter
		private String timerName;
		public String getFlowName(){
			return name;
		}
		private FlowRunnable(String timerName){
			this.timerName = timerName;
		}

		@Override
		public void run() {
			Map<String, String> tags = Map.of("date", dateUtils.getDateString(LocalDateTime.now())
				, "time", dateUtils.getTimeString(LocalDateTime.now()));
			execute(tags);
		}
	}
}
