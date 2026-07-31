package com.quemsi.model.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	public DataVersion(Long id, NamedEntityReference data, List<Tag> tags, List<DataFile> files, LocalDateTime createdAt, String descript, String agentVersion, DatasourceType datasourceType){
		super(id, true);
		this.data = data;
		this.tags = tags;
		this.files = files;
		this.createdAt = createdAt;
		this.descript = descript;
		this.agentVersion = agentVersion;
		this.datasourceType = datasourceType;
	}
    private NamedEntityReference data;
	private NamedEntityReference storage;
	private LocalDateTime createdAt;
	private List<Tag> tags;
	private List<DataFile> files;
	private String descript;
	/** Software version of the agent that produced this backup. */
	private String agentVersion;
	/** DB provider that produced this backup (POSTGRES, SQLSERVER, …). */
	private DatasourceType datasourceType;
	private Long companyId;
	/** Reserved system tag name for {@link #datasourceType} (UI tag filters). */
	public static final String DB_TAG = "db";
	public void setFiles(List<DataFile> fs){
		if(files == null){
			files = fs;
		}else{
			Map<String, DataFile> fileMap = new HashMap<>();
			for (DataFile f : files) {
				fileMap.put(f.getName(), f);
			}
			for (DataFile f : fs) {
				DataFile existing = fileMap.get(f.getName());
				if (existing != null) {
					existing.getStorages().addAll(f.getStorages());
				} else {
					fileMap.put(f.getName(), f);
				}
			}
			files = new ArrayList<>(fileMap.values());
		}
	}
}
