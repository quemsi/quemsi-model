package com.quemsi.model.flow;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.flow.out.Storage;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class To extends AbstractStep {
	private List<Storage> targets;

	@Override
	public void execute(FlowContext context) {
		FlowExecutionStep fes = null;
		try {
			fes = flow.sendStepStarted(context.getExecution().getId(), "To", this.ord , LocalDateTime.now());
			targets.stream().forEach(t -> {
				t.store(context.getFlow().getData().getName(), context.getDataPackages(), context.executionVersion());
				if(t.recordFiles()){
					context.getExecution().getVersion().setFiles(context.getDataPackages().stream().map(dp -> {
						DataFile df = new DataFile();
						df.setActive(true);
						df.setContentType(dp.getContentType());
						df.setDir(context.getDataVersion().getData().getName());
						df.setName(dp.getName());
						df.setSize(dp.getLength());
						df.addStorage(t.getName());
						return df;
					}).toList());
				}
			});
			if(context.isDeleteAfterwards()) {
				context.getDataPackages().stream().forEach(dp-> dp.clear());
			}
			flow.sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
		}catch(BaseRuntimeException bre) {
			context.logError(fes, "error in To step", bre);
			flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
			throw bre;
		}catch(Exception e) {
			context.logError(fes, "error in To step", e);
			flow.sendStepFinished(fes, FlowExecutionStatus.FAILED);
			throw Exceptions.server("error-storing-file").withCause(e).get();
		}
		executeNext(context);
	}
	
	@Override
	public void init(Flow f) {
		targets.forEach(t -> t.init(f));
		super.init(f);
		super.initNext(f);
	}
	
	@Override
	public boolean isReady() {
		return super.isReady() && targets.stream().allMatch(t -> t.isReady());
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", To.class.getSimpleName());
		List<Map<String, Object>> ts = targets.stream().map(t ->{
			Map<String, Object> tprops = new HashMap<>();
			t.fillDetails(tprops);
			return tprops;
		}).collect(Collectors.toList());
		props.put("targets", ts);
		steps.add(props);
		super.fillDetails(steps);
	}
}
