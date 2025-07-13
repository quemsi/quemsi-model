package com.quemsi.model.flow;

import java.util.List;
import java.util.Map;


public interface Step {
	void init(Flow f);
	void initNext(Flow f);
	void setNextStep(Step next);
	void executeNext(FlowContext context);
	void execute(FlowContext context);
	boolean isReady();
	void fillDetails(List<Map<String, Object>> steps);
	void setOrd(Integer ord);
}
