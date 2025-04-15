package com.quemsi.model.flow;

import java.io.InputStream;

import com.quemsi.commons.util.FileResource;


public class DataPackageFileResource implements DataPackage{
    private FileResource file;

    public DataPackageFileResource(FileResource file){
        this.file = file;
    }

    @Override
    public String getName() {
        return file.getName();
    }

    @Override
    public void setName(String name) {
        file.setName(name);
    }

    @Override
    public String getContentType() {
        return file.getContentType();
    }

    @Override
    public void setContentType(String contentType) {
        file.setContentType(contentType);
    }

    @Override
    public java.io.File getFile(String destName) {
        throw new UnsupportedOperationException("Unimplemented method 'getFile'");
    }

    @Override
    public InputStream getInputStream() {
        return file.getInputStream();
    }

    @Override
    public void clear() {
    }

    @Override
    public long getLength() {
        return file.getSize();
    }

}
