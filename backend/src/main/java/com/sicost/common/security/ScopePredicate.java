package com.sicost.common.security;

import java.util.List;
import java.util.Objects;
import static com.sicost.common.security.SecurityModels.*;

public record ScopePredicate(Long userId, Long developerId, Assignments assignments, List<Permission> grants) {
    public ScopePredicate { grants=List.copyOf(grants); }
    public boolean allows(Resource row) { return grants.stream().anyMatch(g->allows(g,row)); }
    public boolean allows(Permission grant, Resource row) {
        if(row==null) return false;
        return switch(grant.scope()) {
            case ALL -> true;
            case PART -> row.partId()!=null && assignments.partIds().contains(row.partId());
            case PROJECT -> row.projectId()!=null && assignments.projectIds().contains(row.projectId());
            case SELF -> Objects.equals(userId,row.userId()) ||
                (developerId!=null && Objects.equals(developerId,row.developerId()));
        };
    }
    public boolean sensitive(Resource row) { return grants.stream().anyMatch(g->g.sensitive() && allows(g,row)); }
    public boolean unrestricted() { return grants.stream().anyMatch(g->g.scope()==DataScope.ALL); }
    public List<Long> partIds() { return assignments.partIds(); }
    public List<Long> projectIds() { return assignments.projectIds(); }
}
