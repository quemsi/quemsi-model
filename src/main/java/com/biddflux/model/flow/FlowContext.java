package com.biddflux.model.flow;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.biddflux.model.dto.DataVersion;
import com.biddflux.model.dto.FlowHistory;
import com.biddflux.model.dto.FlowHistoryStatus;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class FlowContext {
	private boolean deleteAfterwards;
	private FlowHistory flowHistory;
	private DataVersion dataVersion;
	private List<DataPackage> dataPackages;
	private Flow flow;
	private Map<String, String> tags;
	
	public FlowContext(Flow flow) {
		flowHistory = new FlowHistory();
		flowHistory.setActive(true);
		flowHistory.setFlowName(flow.getName());
		flowHistory.setFlowId(flow.getId());
		flowHistory.setStartedAt(new Date(System.currentTimeMillis()));
		this.tags = new HashMap<>();
		this.flow = flow;
		dataPackages = new LinkedList<>();
	}

	public void setDataVersion(DataVersion dataVersion){
		this.dataVersion = dataVersion;
		flowHistory.setVersion(dataVersion);
	}
	
	public boolean inError() {
		return FlowHistoryStatus.ERROR.equals(flowHistory.getStatus());
	}
	public Long executionVersion(){
		return this.dataVersion.getId();
	}
	public void logError(String tag, Exception e) {
		log.error(tag, e);
		flowHistory.setStatus(FlowHistoryStatus.ERROR);
		StringWriter sw = flowHistory.logWriter();
		e.printStackTrace(new PrintWriter(sw));
		flowHistory.setLogs(sw.toString());
	}
}
