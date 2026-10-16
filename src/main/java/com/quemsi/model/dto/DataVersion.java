package com.quemsi.model.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.quemsi.commons.persistence.BaseDto;
import com.quemsi.model.flow.subset.SubsetSnapshot;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DataVersion extends BaseDto<Long>{
	public DataVersion() {
		super();
	}

	@Builder
	public DataVersion(Long id, NamedEntityReference data, List<Tag> tags, List<DataFile> files, LocalDateTime createdAt,
			String descript, String agentVersion, DatasourceType datasourceType, SubsetSnapshot subsetSnapshot){
		super(id, true);
		this.data = data;
		this.tags = tags;
		this.files = files;
		this.createdAt = createdAt;
		this.descript = descript;
		this.agentVersion = agentVersion;
		this.datasourceType = datasourceType;
		this.subsetSnapshot = subsetSnapshot;
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
	/** Subset definition + table counts when this backup used subsetting; null otherwise. */
	private SubsetSnapshot subsetSnapshot;
	private Long companyId;
	/** Reserved system tag name for {@link #datasourceType} (UI tag filters). */
	public static final String DB_TAG = "db";
	/** Reserved system tag name for whether the backup used subsetting (values {@code true}/{@code false}). */
	public static final String SUBSET_TAG = "subset";
	/**
	 * Tags written by a snapshot. Version edits must not rename, retarget, or remove them.
	 * Keep in sync with {@code SYSTEM_TAG_NAMES} in ui-next {@code versionDisplay.js}.
	 */
	public static final Set<String> SYSTEM_TAG_NAMES = Set.of("date", "time", "flow", DB_TAG, SUBSET_TAG, "timer");

	public static boolean isSystemTag(String name) {
		return name != null && SYSTEM_TAG_NAMES.contains(name);
	}
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
