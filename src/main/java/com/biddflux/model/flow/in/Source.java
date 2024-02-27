package com.biddflux.model.flow.in;

import java.util.Map;

import com.biddflux.model.flow.FlowContext;


public interface Source {
	void execute(FlowContext context);
	void fillDetails(Map<String, Object> steps);
}
