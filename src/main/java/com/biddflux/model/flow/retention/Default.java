package com.biddflux.model.flow.retention;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;

import com.biddflux.commons.util.FileNameUtil;
import com.biddflux.commons.util.StringUtils;
import com.biddflux.model.dto.DataFile;
import com.biddflux.model.dto.DataVersion;
import com.biddflux.model.flow.out.Storage;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Default implements RetentionPolicy{
    //TODO: how retention should work in agent
    // @Autowired
    // private DataFileServiceImpl fileService;
    @Autowired
    private FileNameUtil util;
    @Setter
    private Storage storage;
    @Getter
    @Setter
    private long countLimit;
    @Getter
    @Setter
    private long sizeLimit;

    // private Set<String> timeTags = Set.of("date", "time");

    @Override
    public String getName() {
        return "default";
    }
    @Override
    public void clear(){
        List<DataFile> files = null; //fileService.findDataFilesByStorage(storage.getName());
        Long totalSize = files.stream().map(f-> f.getSize()).reduce(Long.valueOf(0L), (a, b) -> a+b);
        if((countLimit <= 0 || files.size() < countLimit) && (sizeLimit <= 0 || totalSize < (sizeLimit * 0.8))){
            log.info("no retention is required for {} files in size of {}", files.size(), totalSize);
            return;
        }
        Set<DataVersion> versionSet = files.stream().map(f -> f.getVersion()).collect(Collectors.toSet());
        LinkedHashMap<String, LinkedList<DataVersion>> versions = new LinkedHashMap<>();
        AtomicInteger counter = new AtomicInteger();
        AtomicLong usedSize = new AtomicLong();
        versionSet.forEach(dv ->{
            String tags = null; 
            // (dv.getTags()==null||dv.getTags().isEmpty())?"notag":dv.getTags().stream().filter(t -> !timeTags.contains(t.getTagValue().getTag().getName()))
            //     .map(t-> t.getTagValue().getTag().getName() + ":" + t.getTagValue().getVal()).collect(Collectors.joining(","));
            if(StringUtils.isEmptyOrNull(tags)){
                tags = "notag";
            }
            if(!versions.containsKey(tags)){
                versions.put(tags, new LinkedList<>());
            }
            versions.get(tags).addLast(dv);
            counter.incrementAndGet();
            dv.getFiles().forEach(df -> usedSize.set(usedSize.get() + df.getSize()));
        });
        while(((countLimit > 0L && counter.get() > countLimit) || (sizeLimit >0L && usedSize.get() >= (sizeLimit * 0.75)))){
            versions.forEach((k, v) -> {
                if(v.size() > 1L){
                    DataVersion version = v.getLast();
                    version.getFiles().forEach(f -> {
                        try{
                            storage.deleteFile(f.getDir(), util.versionedFileName(f.getName(), f.getVersion().getId()) );
                            // fileService.deleteById(f.getId());
                            counter.decrementAndGet();
                            usedSize.set(usedSize.get() - f.getSize());
                        }catch(IOException e){
                            log.error("io-exception-deleting-file :" + f.getId(), e);
                        }
                    });
                }
            });
        }
        log.info("after retention of tags {} files in size of {}", files.size(), usedSize);
    }
}
