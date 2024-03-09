package com.biddflux.model.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.biddflux.commons.util.BaseRuntimeException;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.dto.DataFile;
import com.biddflux.model.flow.out.Storage;

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
				t.store(context.getFlow().getData().getName(), context.getDataPackages(), context.executionVersion());
				if(t.recordFiles()){
					context.getFlowHistory().getVersion().setFiles(context.getDataPackages().stream().map(dp -> {
						DataFile df = new DataFile();
						df.setActive(true);
						df.setContentType(dp.getContentType());
						df.setDir(context.getDataVersion().getData().getName());
						df.setName(dp.getName());
						df.setSize(dp.getLength());
						df.setStorage(t.getName());
						return df;
					}).toList());
				}
			});
			if(context.isDeleteAfterwards()) {
				context.getDataPackages().stream().forEach(dp-> dp.clear());
			}
		}catch(BaseRuntimeException bre) {
			throw bre;
		}catch(Exception e) {
			context.logError("error storing file", e);
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
