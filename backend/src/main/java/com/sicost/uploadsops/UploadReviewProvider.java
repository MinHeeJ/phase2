package com.sicost.uploadsops;

import com.sicost.common.api.DeveloperPaging;
import com.sicost.common.api.PageResult;

public interface UploadReviewProvider {
    boolean owns(long uploadId);
    boolean supports(String form);
    PageResult<PersonnelUploadModels.Issue> issues(long id, String severity, String column, DeveloperPaging paging);
    PageResult<?> rows(long id, String severity, DeveloperPaging paging);
    PersonnelUploadModels.Result revalidate(long id);
    UploadModels.UploadFile errors(long id);
    UploadModels.UploadFile template();
}
