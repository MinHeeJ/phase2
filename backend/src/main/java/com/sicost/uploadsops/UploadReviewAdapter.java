package com.sicost.uploadsops;

import com.sicost.common.api.DeveloperPaging;
import com.sicost.common.api.PageResult;
import static com.sicost.uploadsops.PersonnelUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

public interface UploadReviewAdapter {
    boolean supports(long id);
    boolean supportsForm(String form);
    Result result(long id);
    PageResult<Issue> issues(long id, String severity, String column, DeveloperPaging paging);
    Object rows(long id, String severity, DeveloperPaging paging);
    Result revalidate(long id);
    Object apply(long id);
    UploadFile errors(long id);
    UploadFile template();
}
