package com.quemsi.model.flow.out;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.quemsi.model.dto.DataFile;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.Flow;

import lombok.Setter;

public class ABStorage extends AbstractStorage{
    @Setter
    private AzureBlobDrive azureBlobDrive;
    @Setter
    private Storage underlyingStorage;
    
    @Override
    public void store(String dataName, List<DataPackage> dataPackages, Long version) {
        underlyingStorage.store(dataName, dataPackages, version);
    }

    @Override
    public List<DataPackage> getFiles(List<DataFile> files) throws IOException {
        return underlyingStorage.getFiles(files);
    }

    @Override
    public void deleteFile(String dir, String fileName) throws IOException {
        underlyingStorage.deleteFile(dir, fileName);
    }

    @Override
    public boolean isReady() {
        return underlyingStorage.isReady();
    }

    @Override
    public void fillDetails(Map<String, Object> props) {
        underlyingStorage.fillDetails(props);
    }

    @Override
    public void init(Flow f) {
        underlyingStorage.init(f);
    }

}
