package com.quemsi.model.dto;

import java.io.StringWriter;
import java.util.Date;

import com.quemsi.commons.persistence.BaseDto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlowHistory extends BaseDto<Long>{
	private Long flowId;
	private String flowName;
	private Date startedAt;
	private Date finishedAt;
	private FlowExecutionStatus status;
	private DataVersion version;
	private String logs;
	
	public StringWriter logWriter() {
		StringWriter sw = new StringWriter();
		if(logs != null) {
			sw.write(logs);
		}
		return sw;
	}
}
