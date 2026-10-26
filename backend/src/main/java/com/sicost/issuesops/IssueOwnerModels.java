package com.sicost.issuesops;

import java.time.LocalDate;

public final class IssueOwnerModels {
    private IssueOwnerModels() {}
    public record IssueOwnerOption(String name) {}
    public record IssueOwnerQuery(long projectId, LocalDate today) {}
}
