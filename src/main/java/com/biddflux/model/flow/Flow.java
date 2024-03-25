package com.biddflux.model.flow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.util.BaseRuntimeException;
import com.biddflux.commons.util.DateUtils;
import com.biddflux.model.dto.DataGroup;
import com.biddflux.model.dto.DataVersion;
import com.biddflux.model.dto.FlowDetail;
import com.biddflux.model.dto.FlowHistory;
import com.biddflux.model.dto.FlowHistoryStatus;
import com.biddflux.model.dto.NamedEntityReference;
import com.biddflux.model.dto.Tag;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class Flow {
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
	
	public void initialize() {
		first.init(this);
	}
	
	public FlowHistory execute(FlowContext fc){
		try{
			if(lock.tryLock()) {
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
				return fc.getFlowHistory();
			}else {
				log.info("{} flow is already running", this.getName());
				return null;
			}
		}finally{
			lock.unlock();
		}
	}

	public FlowHistory execute(Long versionId, Map<String, String> tags) {
		FlowContext fc = new FlowContext(this);
		fc.setTags(tags);
		fc.setDataVersion(DataVersion.builder().id(versionId).data(NamedEntityReference.builder().id(data.getId()).name(data.getName()).build()).build());
		return execute(fc);
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
}
