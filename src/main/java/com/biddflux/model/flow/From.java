package com.biddflux.model.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.biddflux.EnvironmentVars;
import com.biddflux.commons.util.BaseRuntimeException;
import com.biddflux.model.flow.in.Source;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class From extends AbstractStep {
	private Source source;
	
	@Override
	public void execute(FlowContext context) {
		try {
			source.execute(context);
			executeNext(context);
		}catch(BaseRuntimeException bre) {
			throw bre;
		}catch(Exception e) {
			context.logError("eror in from", e);
		}
	}
	
	@Override
	public void init(Flow f, EnvironmentVars env) {
		super.init(f, env);
		super.initNext(f, env);
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", From.class.getSimpleName());
		Map<String, Object> sProps = new HashMap<>();
		source.fillDetails(sProps);
		props.put("source", sProps);
		steps.add(props);
		super.fillDetails(steps);
	}
}
