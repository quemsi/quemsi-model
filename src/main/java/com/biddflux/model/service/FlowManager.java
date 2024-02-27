package com.biddflux.model.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.EnvironmentVars;
import com.biddflux.commons.util.BaseRuntimeException;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.dto.FlowDetail;
import com.biddflux.model.flow.Flow;
import com.biddflux.model.flow.Step;
import com.biddflux.model.flow.Timer;
import com.biddflux.model.flow.factories.StepFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FlowManager {
	private Map<String, Flow> flows = new HashMap<>();
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private StepFactory stepFactory;
	@Autowired
	private ObjectProvider<Flow> flowObjectProvider;
	@Autowired
	private SpringBeanManager beanManager;
	@Autowired
	private EnvironmentVars env;
	
	public Flow createNewFlow(FlowDetail flow) {
		String model = null;
		try {
			model = flow.getModel();
			JsonNode node = objectMapper.readTree(model.getBytes(Charset.forName("UTF-8")));
			Flow f = flowObjectProvider.getObject();
			String name = node.findValue("name").asText(null);
			f.setId(flow.getId());
			f.setName(name);
			f.setActive(flow.isActive());
			f.setTitle(flow.getTitle());
			f.setData(flow.getData());
			f.setBack(flow.isBack());
			log.info("retention policy for flow {} is {}", f.getName(), node.findValue("retentionPolicy").asText(null));
			JsonNode steps = node.get("steps");
			LinkedList<Step> stepList = new LinkedList<>();
			if(steps != null && steps.isArray()) {
				for(JsonNode step : steps){
					Step s = stepFactory.from(step);
					stepList.add(s);
				}
			}
			f.setSteps(stepList);
			flows.put(name, f);
			if(flow.getTimer() != null){
				Timer timer = beanManager.findTimer(flow.getTimer());
				timer.add(f.getRunnable(timer.getName()));
			}
			f.initialize(env);
			return f;
		} catch (IOException e) {
			log.error("unable-to-create-flow", Exceptions.server("invalid-flow-model").withExtra("model", model).withCause(e).get());
		} catch (BaseRuntimeException bre){
			log.error("error-in-creating-flow", bre);
		}
		return null;
	}
	
	public Optional<Flow> findByName(String name) {
		return !flows.containsKey(name)?Optional.empty():Optional.of(flows.get(name));
	}
}
