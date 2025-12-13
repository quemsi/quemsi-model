package com.quemsi.model.flow.process;

import java.security.SecureRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.MaskType;

public class MaskedStringGenerator {
    private static final SecureRandom secureRandom = new SecureRandom();
    private LoadingCache<Integer,String> maskCache;
    private MaskType maskType;
    private String maskChar;
    private int length;
    
    public void setMaskType(MaskType maskType) {
        this.maskType = maskType;
    }
    
    public void setMaskChar(String maskChar) {
        this.maskChar = maskChar;
        // Rebuild cache with the new maskChar
        rebuildCache();
    }
    
    public void setLength(int length) {
        this.length = length;
    }
    
    private void rebuildCache() {
        if (maskChar != null) {
            CacheLoader<Integer, String> loader = new CacheLoader<Integer, String>() {
                @Override
                public String load(Integer key) {
                    return maskChar.repeat(key);
                }
            };
            maskCache = CacheBuilder.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build(loader);
        }
    }

    public MaskedStringGenerator(){
        // Cache will be built when maskChar is set
    }
    public String generate(String originalString, int maxLength){
        int maskLength = 0;
        if(maskType == MaskType.FIXED){
            maskLength = Math.min(length, maxLength);
        } else if(maskType == MaskType.ORIGINAL){
            maskLength = originalString.length();
        } else if(maskType == MaskType.RANDOM){
            maskLength = secureRandom.nextInt(Math.min(100, maxLength)) + 1;
        }
        if(maskLength > 0){
            try {
                return maskCache.get(maskLength);
            } catch (ExecutionException e) {
                throw Exceptions.server("error-generating-masked-string").withCause(e).get();
            }
        } else {
            throw Exceptions.badRequest("invalid-mask-length").get();
        }
    }
}
