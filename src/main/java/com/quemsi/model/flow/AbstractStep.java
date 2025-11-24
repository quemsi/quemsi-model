package com.quemsi.model.flow;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractStep implements Step {
	@Getter
	@Setter
	protected Integer ord;
	protected boolean initialized;
	@Setter
	protected Flow flow;
	
	@Override
	public void init(Flow f) {
		this.flow = f;
		initialized = true;
	}
	
	@Override
	public String getType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public boolean isReady() {
		return this.initialized;
	}
}
