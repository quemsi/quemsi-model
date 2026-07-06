package com.quemsi.model.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.flow.out.Storage;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class To extends AbstractStep {
	private List<Storage> targets;

	@Override
	public void execute(FlowContext context) {
		try {
			targets.stream().forEach(t -> {
				context.logStepInfo( context.getCurrentStep(), LogMessage.info("Storing files in {} storage", t.getName()));
				t.store(context, context.getFlow().getData().getName(), context.getDataPackages(), context.executionVersion());
				context.logStepInfo( context.getCurrentStep(), LogMessage.info("Stored files in {} storage", t.getName()));
				if(t.recordFiles()){
					context.logStepInfo( context.getCurrentStep(), LogMessage.info("Recording files in {} storage", t.getName()));
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
					context.logStepInfo( context.getCurrentStep(), LogMessage.info("Recorded files in {} storage", t.getName()));
				}
			});
			if(context.isDeleteAfterwards()) {
				context.logStepInfo( context.getCurrentStep(), LogMessage.info("Clearing data packages"));
				context.getDataPackages().stream().forEach(dp-> dp.clear());
				context.logStepInfo( context.getCurrentStep(), LogMessage.info("Cleared data packages"));
			}
		}catch(Exception e) {
			throw Exceptions.server("error-storing-file").withCause(e).get();
		}
	}
	
	@Override
	public void init(Flow f) {
		super.init(f);
		targets.forEach(t -> t.init(f));
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
	}
}
