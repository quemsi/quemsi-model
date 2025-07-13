package com.quemsi.model.flow.os;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.quemsi.model.flow.AbstractStep;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ShellCommand extends AbstractStep{
	@Setter
	private String script;
	@Setter
	private String supass;
	
	@Override
	public void execute(FlowContext context) {
        try {
        	StringBuilder sb = new StringBuilder();
        	if(supass != null && !"".equals(supass.trim())) {
        		sb.append("/usr/bin/sudo -S ");
        	}
        	sb.append(script);
        	Process process = new ProcessBuilder(new String[]{"/bin/bash", "-c", sb.toString()}).start();
            if(supass != null && !"".equals(supass.trim())) {
            	process.getOutputStream().write(supass.getBytes());
            }
            InputStream inputStream = process.getInputStream(); 
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                log.debug(line);
            }
            this.executeNext(context);
        } catch(Exception ex) {
            log.error("error in script : " + script + ex);
        }
	}
	
	@Override
	public void init(Flow f) {
		super.init(f);
		super.initNext(f);
	}
	
	@Override
	public void fillDetails(List<Map<String, Object>> steps) {
		Map<String, Object> props = new HashMap<>();
		props.put("type", ShellCommand.class.getSimpleName());
		props.put("script", this.script);
		String maskedSuPass = this.supass!=null&&!"".equals(this.supass)?"*****":this.supass;
		props.put("supass", maskedSuPass);
		steps.add(props);
		super.fillDetails(steps);
	}
}
