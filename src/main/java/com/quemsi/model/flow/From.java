package com.quemsi.model.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.quemsi.model.flow.in.Source;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class From extends AbstractStep {
	private Source source;
	
	@Override
	public void execute(FlowContext context) {
		source.execute(context);
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", From.class.getSimpleName());
		Map<String, Object> sProps = new HashMap<>();
		source.fillDetails(sProps);
		props.put("source", sProps);
		steps.add(props);
	}
}
