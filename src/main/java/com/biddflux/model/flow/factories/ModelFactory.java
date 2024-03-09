package com.biddflux.model.flow.factories;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.flow.in.StoredData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
                                @SuppressWarnings("unchecked")
                                Map<String, String> tags = objectMapper.convertValue(tagsNode, Map.class);
                                sd.setTags(tags);
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
