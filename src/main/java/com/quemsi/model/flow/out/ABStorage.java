package com.quemsi.model.flow.out;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.quemsi.commons.util.FileNameUtil;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.dto.DataType;
import com.quemsi.model.flow.DataPackage;

import lombok.Setter;

public class ABStorage extends AbstractStorage{
    @Setter
    private AzureBlobDrive azureBlobDrive;
    @Setter
    private String retentionPolicy;
    @Setter
    private Long usedSize;
    @Setter
    private Long capacity;
    @Setter
    private FileNameUtil util;
    
    @Override
    public void store(String dataName, List<DataPackage> dataPackages, Long version) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'store'");
    }

    @Override
    public List<DataPackage> getDataPackage(String dataName, DataType type, Long version) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getDataPackage'");
    }

    @Override
    public List<DataPackage> getFiles(List<DataFile> files) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getFiles'");
    }

    @Override
    public void deleteFile(String dir, String fileName) throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteFile'");
    }

    @Override
    public boolean isReady() {
        throw new UnsupportedOperationException("unimplemented ye");
    }

    @Override
    public void fillDetails(Map<String, Object> props) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'fillDetails'");
    }

}
