package com.biddflux.model.flow.out;

import java.io.IOException;
import java.io.InputStream;

import com.google.api.client.http.AbstractInputStreamContent;

import lombok.Getter;

public class GoogleInputStreamContent extends AbstractInputStreamContent {
    @Getter
    private String name;
    private long length;
    private InputStream inputStream;

    public GoogleInputStreamContent(String name, String type, InputStream inputStream, long length) {
        super(type);
        this.inputStream = inputStream;
        this.length = length;
        this.name = name;
    }

    @Override
    public long getLength() throws IOException {
        return length;
    }

    @Override
    public boolean retrySupported() {
        return false;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return inputStream;
    }

}
