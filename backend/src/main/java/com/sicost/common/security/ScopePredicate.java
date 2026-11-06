package com.sicost.common.security;

import com.sicost.common.api.ApiException;
import java.util.List;
import java.util.Set;

/** Only action-qualified grants enter this predicate; never combine one role's action with another's scope. */
public record ScopePredicate(long userId, Long developerId, List<Grant> grants) {
    public ScopePredicate { grants = List.copyOf(grants); }
    public record Target(Long partId, Long projectId, Long userId, Long developerId) {}
    public record Grant(long roleId, DataScope scope, Set<Long> partIds, Set<Long> projectIds,
                        boolean sensitive, boolean partStandardChange) {
        public Grant { partIds = Set.copyOf(partIds); projectIds = Set.copyOf(projectIds); }
    }
    private boolean matches(Grant grant, Target target) {
        return switch (grant.scope()) {
            case ALL -> true;
            case PART -> target.partId() != null && grant.partIds().contains(target.partId());
            case PROJECT -> target.projectId() != null && grant.projectIds().contains(target.projectId());
            case SELF -> (target.userId() != null && target.userId() == userId) ||
                (developerId != null && developerId.equals(target.developerId()));
        };
    }
    public boolean allows(Target target) { return target != null && grants.stream().anyMatch(g -> matches(g,target)); }
    public boolean sensitive(Target target) { return target != null && grants.stream().anyMatch(g -> g.sensitive() && matches(g,target)); }
    public boolean partStandardChange(Target target) { return target != null && grants.stream().anyMatch(g -> g.partStandardChange() && matches(g,target)); }
    public void require(Target target) { if (!allows(target)) throw ApiException.notFound("요청한 항목이 없습니다."); }
    public void requireAll(List<Target> targets) { for (Target target : targets) require(target); }
}
