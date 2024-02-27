package com.biddflux.model.dto;

import java.io.StringWriter;
import java.util.Date;

import com.biddflux.commons.persistence.BaseDto;
import com.biddflux.commons.persistence.Views;
import com.fasterxml.jackson.annotation.JsonView;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class FlowHistory extends BaseDto<Long>{
	@JsonView(Views.OnlyIdName.class)
	private Long flowId;
	@JsonView(Views.OnlyIdName.class)
	private String flowName;
	@JsonView(Views.BasicInfo.class)
	private Date startedAt;
	@JsonView(Views.BasicInfo.class)
	private Date finishedAt;
	@JsonView(Views.BasicInfo.class)
	private FlowHistoryStatus status;
	@JsonView(Views.BasicInfo.class)
	private DataVersion version;
	@JsonView(Views.BasicInfo.class)
	private String logs;
	
	public StringWriter logWriter() {
		StringWriter sw = new StringWriter();
		if(logs != null) {
			sw.write(logs);
		}
		return sw;
	}
}
