package com.quemsi.model.flow;

import java.util.List;
import java.util.Map;


public interface Step {
	void init(Flow f);
	void execute(FlowContext context);
	boolean isReady();
	void fillDetails(List<Map<String, Object>> steps);
	void setOrd(Integer ord);
	Integer getOrd();
	String getType();
}
