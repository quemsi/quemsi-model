package com.quemsi.model.service;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.FileResource;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.DataPackageFileResource;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.util.CommonHelpers;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TableDataPersister {
    @Setter
    private ObjectMapper objectMapper;
    public Map<String, TableData> tableDataMap = new ConcurrentHashMap<>();
    
    public void persist(TableDataPage tableDataPage){
        tableDataMap.compute(tableDataPage.getRequest().getTable().qualifiedName(), (key, td) -> {
            if(td == null){
                td = new TableData(tableDataPage.getRequest().getTable().qualifiedName());
            }
            td.getDataPages().add(new TableData.DataPage(tableDataPage.getRequest().getPageNum(), tableDataPage.getTableData()));
            return td;
        });
    }

    public List<DataPackage> getDataPackages(){
        LinkedList<DataPackage> dataPackages = new LinkedList<>();
        tableDataMap.entrySet().forEach(Exceptions.wrapConsumer(e -> {
            log.info("serializing table data for {}", e.getKey());
            TableData tableData = e.getValue();
            String tableDataJson = objectMapper.writeValueAsString(tableData);
            log.info("table data json size: {}", tableDataJson.length());
            byte[] dataPagesJsonBytes = tableDataJson.getBytes();
            String fileName = CommonHelpers.dataFileName(e.getKey());
            FileResource tData = FileResource.builder()
                .name(fileName).originalFilename(fileName).contentType(MediaType.APPLICATION_JSON_VALUE)
                .empty(false).size(dataPagesJsonBytes.length).data(dataPagesJsonBytes)
                .build();
            DataPackageFileResource tDataResource = new DataPackageFileResource(tData.getName(), tData);
            dataPackages.add(tDataResource);
        }));
        return dataPackages;
    }
}
