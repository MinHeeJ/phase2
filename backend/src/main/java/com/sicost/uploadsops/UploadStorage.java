package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class UploadStorage {
    private final Path root;
    public UploadStorage(@Value("${app.upload-directory:${java.io.tmpdir}/sicost-uploads}") String directory) {root=Path.of(directory).toAbsolutePath().normalize();}
    public Path save(MultipartFile file,Clock clock) {
        var month=YearMonth.now(clock);Path directory=root.resolve(String.format("%04d/%02d",month.getYear(),month.getMonthValue()));
        try {
            Files.createDirectories(directory);Path target=directory.resolve(UUID.randomUUID()+".xlsx");
            try(var in=file.getInputStream();var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {in.transferTo(out);}
            return target;
        } catch(IOException ex){throw new UncheckedIOException("파일을 저장할 수 없습니다.",ex);}
    }
    public Path existing(String address) {
        if(address==null)throw ApiException.notFound("원본 파일이 없습니다.");
        Path path=Path.of(address).toAbsolutePath().normalize();
        if(!path.startsWith(root)||!Files.isRegularFile(path))throw ApiException.notFound("원본 파일이 없습니다.");
        return path;
    }
}
