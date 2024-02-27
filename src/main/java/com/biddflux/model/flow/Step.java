package com.biddflux.model.flow;

import java.util.List;
import java.util.Map;

import com.biddflux.EnvironmentVars;


public interface Step {
	void init(Flow f, EnvironmentVars env);
	void initNext(Flow f, EnvironmentVars env);
	void setEnv(EnvironmentVars env);
	void setNextStep(Step next);
	void executeNext(FlowContext context);
	void execute(FlowContext content);
	boolean isReady();
	void fillDetails(List<Map<String, Object>> steps);
}
