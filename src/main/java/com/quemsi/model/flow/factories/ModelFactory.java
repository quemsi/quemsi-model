package com.quemsi.model.flow.factories;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.in.StoredData;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ModelFactory {
    @Autowired
    private ObjectMapper objectMapper;

    public StoredData findStoredData(String model){
        try {
			JsonNode node = objectMapper.readTree(model.getBytes(Charset.forName("UTF-8")));
			JsonNode steps = node.get("steps");
			
            if(steps != null && steps.isArray()) {
				for(JsonNode step : steps){
					String type = step.get("type").asText(null);
                    if("From".equals(type)){
                        JsonNode source = step.get("source");
                        if(source != null){
                            type = source.get("type").asText(null);
                            if("StoredData".equals(type)){
                                StoredData sd = new StoredData();
                                String version = source.get("version").asText();
                                sd.setVersion(version);
                                JsonNode tagsNode = source.get("tags");
                                if(tagsNode.isArray()){
                                    Map<String, String> tags = new HashMap<>();
                                    Iterator<JsonNode> it = tagsNode.iterator();
                                    while(it.hasNext()){
                                        JsonNode t = it.next();
                                        String name = t.get("name").asText();
                                        String value = t.get("value").asText();
                                        tags.put(name, value);
                                    }
                                    sd.setTags(tags);   
                                }
                                return sd;
                            }
                        }
					}
				}
			}
		} catch (IOException e) {
			log.error("unable-to-parse-model", Exceptions.server("invalid-flow-model").withExtra("model", model).withCause(e).get());
		} catch (Exception ex){
			log.error("error-in-parsing-model", ex);
		}
        return null;
    }
}
