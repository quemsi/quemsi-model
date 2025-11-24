package com.quemsi.model.flow.out;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.quemsi.commons.util.FileNameUtil;
import com.quemsi.model.dto.DataFile;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.Flow;
import com.quemsi.model.flow.FlowContext;

import lombok.Setter;

public class AWS3Storage extends AbstractStorage{
    @Setter
    private AWSS3Drive awsS3Drive;
    @Setter
    private Storage underlyingStorage;
    @Setter
    private String retentionPolicy;
    @Setter
    private Long usedSize;
    @Setter
    private Long capacity;
    @Setter
    private FileNameUtil util;
    
    @Override
    public void store(FlowContext context, String dataName, List<DataPackage> dataPackages, Long version) {
        underlyingStorage.store(context, dataName, dataPackages, version);
    }

    @Override
    public List<DataPackage> getFiles(FlowContext context, List<DataFile> files) throws IOException {
        return underlyingStorage.getFiles(context, files);
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
