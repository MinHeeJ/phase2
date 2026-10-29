package com.sicost.uploadsops;

import com.sicost.common.api.DeveloperPaging;
import com.sicost.common.api.PageResult;
import java.nio.file.Path;
import static com.sicost.uploadsops.PersonnelUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

public interface UploadFormDelegate {
    boolean supports(String form);
    boolean owns(long id);
    Result validate(String month, long projectId, String name, Path path);
    Result revalidate(long id);
    PageResult<Issue> issues(long id, String severity, String column, DeveloperPaging paging);
    PageResult<?> rows(long id, String severity, DeveloperPaging paging);
    Object apply(long id);
    UploadFile errors(long id);
    UploadFile template();
}
