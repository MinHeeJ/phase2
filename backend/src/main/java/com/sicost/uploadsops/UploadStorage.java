package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import static com.sicost.uploadsops.UploadModels.*;

@Component
public class UploadStorage {
    static final long MAX_BYTES=10L*1024*1024;
    private final Path root;
    private final Clock clock;
    public UploadStorage(@Value("${sicost.upload-directory:${java.io.tmpdir}/sicost/uploads}") String root,Clock clock) {
        this.root=Path.of(root).toAbsolutePath().normalize();this.clock=clock;
    }
    Clock clock() {return clock;}
    Original save(MultipartFile file) {
        String supplied=file.getOriginalFilename();
        if(file.getSize()>MAX_BYTES)throw new ApiException(413,"PAYLOAD_TOO_LARGE","파일은 10MB 이하로 올려 주세요.","file");
        if(supplied==null||!supplied.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","xlsx 파일만 올려 주세요.");
        if(file.isEmpty())throw PartnerUploadForm.fileError("EMPTY_FILE","데이터가 없습니다.");
        String name=supplied.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
        if(name.length()>180)name=name.substring(0,175)+".xlsx";
        LocalDate today=LocalDate.now(clock);Path folder=root.resolve(String.format(Locale.ROOT,"%04d/%02d",today.getYear(),today.getMonthValue()));
        try {
            Files.createDirectories(folder);Path path=folder.resolve(UUID.randomUUID()+".xlsx");
            try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[8192];long count=0;int read;
                while((read=in.read(buffer))!=-1) {count+=read;if(count>MAX_BYTES) {out.close();Files.deleteIfExists(path);throw new ApiException(413,"PAYLOAD_TOO_LARGE","파일은 10MB 이하로 올려 주세요.","file");}out.write(buffer,0,read);}
            }
            return new Original(name,path.toString());
        } catch(IOException ex) {throw new ApiException(500,"SERVER_ERROR","파일을 저장할 수 없습니다.","file");}
    }
    InputStream open(Original original) {
        if(original==null||original.path()==null)throw missing();
        Path path=Path.of(original.path()).toAbsolutePath().normalize();
        if(!path.startsWith(root)||!Files.isRegularFile(path))throw missing();
        try {return Files.newInputStream(path);}catch(IOException ex) {throw missing();}
    }
    private ApiException missing() {return ApiException.notFound("원본 파일이 없습니다. 파일을 다시 올려 주세요.");}
}
