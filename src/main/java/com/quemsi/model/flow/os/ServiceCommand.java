package com.quemsi.model.flow.os;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.quemsi.model.dto.FlowExecution.FlowExecutionStep;
import com.quemsi.model.dto.FlowExecutionStatus;
import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServiceCommand extends AbstractStep{
	@Setter
	private String name;
	@Setter
	private String action;
	
	@Override
	public void execute(FlowContext context) {
        FlowExecutionStep fes = null;
		try {
        	fes = flow.sendStepStarted(context.getExecution().getId(), "ServiceCommand", this.ord , LocalDateTime.now());
			String[] command = {"cmd.exe", "/c", "net", action, name};
            Process process = new ProcessBuilder(command).start();
            InputStream inputStream = process.getInputStream(); 
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                log.debug(line);
            }
			flow.sendStepFinished(fes, FlowExecutionStatus.SUCCESS);
        } catch(Exception ex) {
            context.logError(fes, "error in ServiceCommand step", ex);
        }
        this.executeNext(context);
	}
	
	@Override
	public void init(Flow f) {
		super.init(f);
		initNext(f);
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", ServiceCommand.class.getSimpleName());
		props.put("name", name);
		props.put("action", action);
		steps.add(props);
		super.fillDetails(steps);
	}
}
