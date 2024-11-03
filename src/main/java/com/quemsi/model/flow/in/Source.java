package com.quemsi.model.flow.in;

import java.util.Map;

import com.quemsi.model.flow.FlowContext;


public interface Source {
	void execute(FlowContext context);
	void fillDetails(Map<String, Object> steps);
}
