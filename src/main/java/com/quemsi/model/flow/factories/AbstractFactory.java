package com.quemsi.model.flow.factories;

import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.quemsi.commons.util.Exceptions;
import com.fasterxml.jackson.databind.JsonNode;

public abstract class AbstractFactory<T> {
	@Autowired
	protected ApplicationContext context;
	
	public abstract Map<String, Function<JsonNode, T>> getBuilders();
	
	public String type(JsonNode step){
		return step.get("type").asText(null);
	}

	public T from(JsonNode step) {
		String type = type(step);
		if(!getBuilders().containsKey(type)) {
			throw Exceptions.badRequest("not-supported-object-type").withExtra("type", type).get();
		}
		return getBuilders().get(type).apply(step);
	}
}
