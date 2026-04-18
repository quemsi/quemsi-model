package com.quemsi.model.flow.in;

import java.util.function.Consumer;
import java.util.function.Supplier;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public interface CustomSerializedColumn {
    String getDbType();
    String getDataId();
    public byte[] getData();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BinaryColumn implements CustomSerializedColumn{
        private String dbType;
        private String dataId;
        private byte[] data;
    }
}
