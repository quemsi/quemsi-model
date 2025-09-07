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

import lombok.Setter;

public class TableDataPersister {
    @Setter
    private ObjectMapper objectMapper;
    public Map<String, TableData> tableDataMap = new ConcurrentHashMap<>();
    
    public void persist(TableDataPage tableDataPage){
        tableDataMap.compute(tableDataPage.getRequest().getTable().getName(), (key, td) -> {
            if(td == null){
                td = new TableData(tableDataPage.getRequest().getTable().getName());
            }
            td.getDataPages().add(new TableData.DataPage(tableDataPage.getRequest().getPageNum(), tableDataPage.getTableData()));
            return td;
        });
    }

    public List<DataPackage> getDataPackages(){
        LinkedList<DataPackage> dataPackages = new LinkedList<>();
        tableDataMap.entrySet().forEach(Exceptions.wrapConsumer(e -> {
            TableData tableData = e.getValue();
            String tableDataJson = objectMapper.writeValueAsString(tableData);
            byte[] dataPagesJsonBytes = tableDataJson.getBytes();
            String fileName = "data-" +e.getKey() + ".json";
            FileResource tData = FileResource.builder()
                .name(fileName).originalFilename(fileName).contentType(MediaType.APPLICATION_JSON_VALUE)
                .empty(false).size(dataPagesJsonBytes.length).data(dataPagesJsonBytes)
                .build();
            DataPackageFileResource tDataResource = new DataPackageFileResource(tData);
            dataPackages.add(tDataResource);
        }));
        return dataPackages;
    }
}
