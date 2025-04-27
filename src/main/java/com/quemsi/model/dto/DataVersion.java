package com.quemsi.model.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.quemsi.commons.persistence.BaseDto;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DataVersion extends BaseDto<Long>{
	@Builder
	public DataVersion(Long id, NamedEntityReference data, List<Tag> tags, List<DataFile> files, LocalDateTime createdAt, String descript){
		super(id, true);
		this.data = data;
		this.tags = tags;
		this.files = files;
		this.createdAt = createdAt;
		this.descript = descript;
	}
    private NamedEntityReference data;
	private NamedEntityReference storage;
	private LocalDateTime createdAt;
	private List<Tag> tags;
	private List<DataFile> files;
	private String descript;
	private Long companyId;
}
