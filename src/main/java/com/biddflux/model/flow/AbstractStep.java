package com.biddflux.model.flow;

import java.util.List;
import java.util.Map;

import com.biddflux.EnvironmentVars;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractStep implements Step {
	@Setter
	protected Step nextStep;
	@Setter
	protected EnvironmentVars env;
	protected boolean initialized;
	@Setter
	protected Flow flow;
	@Override
	public void init(Flow f, EnvironmentVars env) {
		this.flow = f;
		initialized = true;
	}
	
	@Override
	public void executeNext(FlowContext context) {
		if(nextStep != null) {
			nextStep.execute(context);
		}
	} 
	@Override
	public void initNext(Flow f, EnvironmentVars env) {
		if(nextStep != null) {
			nextStep.init(f, env);
		}else {
			log.debug("flow intialization is completed");
		}
	} 
	@Override
	public boolean isReady() {
		return nextStep!=null?this.initialized && nextStep.isReady():this.initialized;
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		if(this.nextStep != null) {
			this.nextStep.fillDetails(steps);
		}
	}
}
