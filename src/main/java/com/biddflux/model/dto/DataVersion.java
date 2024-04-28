package com.biddflux.model.dto;

import java.util.List;

import com.biddflux.commons.persistence.BaseDto;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DataVersion extends BaseDto<Long>{
	@Builder
	public DataVersion(Long id, NamedEntityReference data, List<Tag> tags, List<DataFile> files){
		super(id, true);
		this.data = data;
		this.tags = tags;
		this.files = files;
	}
    private NamedEntityReference data;
	private List<Tag> tags;
	private List<DataFile> files;
}
