package com.sicost.staffrecommendations;

import com.sicost.common.ai.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RecommendationBoundary {
    private final AiAccessGuard access;
    private final AiOwnerSession owners;
    private final AiSensitiveGuard sensitive;
    public RecommendationBoundary(AiAccessGuard access,AiOwnerSession owners,AiSensitiveGuard sensitive) {
        this.access=access;this.owners=owners;this.sensitive=sensitive;
    }
    public String enter(HttpServletRequest request,String action) {access.requireAction(action);return owners.ownerHash(request);}
    public void own(HttpServletRequest request,String owner) {access.requireDevelopment();owners.requireOwner(request,owner);}
    public void current(HttpServletRequest request,String owner) {owners.assertCurrent(request,owner);}
    public void synthetic() {sensitive.requireSyntheticDatabase();}
}
