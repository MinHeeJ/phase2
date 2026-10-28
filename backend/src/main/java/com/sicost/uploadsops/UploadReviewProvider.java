package com.sicost.uploadsops;

import com.sicost.common.api.DeveloperPaging;
import com.sicost.common.api.PageResult;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.UploadModels.UploadFile;

public interface UploadReviewProvider {
    String formType();
    boolean owns(long uploadId);
    PageResult<Issue> issues(long uploadId, String severity, String column, DeveloperPaging paging);
    PageResult<?> rows(long uploadId, String severity, DeveloperPaging paging);
    UploadFile errors(long uploadId);
    UploadFile template();
}
