package com.quemsi.model.flow;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.flow.in.Source;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class From extends AbstractStep {
	private Source source;
	
	@Override
	public void execute(FlowContext context) {
		FlowExecutionStep fes = null;
		try {
			fes = flow.sendStepStarted(context.getExecution().getId(), "From", this.ord , LocalDateTime.now());
			source.execute(context);
			flow.sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
		}catch(BaseRuntimeException bre) {
			context.logError(fes, "Erro in From step", bre);
			flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
			throw bre;
		}catch(Exception e) {
			context.logError(fes, "Unexpected expection in From step", e);
			flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
		}
		executeNext(context);
	}
	
	@Override
	public void init(Flow f) {
		super.init(f);
		super.initNext(f);
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
