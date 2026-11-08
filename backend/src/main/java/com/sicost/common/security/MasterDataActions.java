package com.sicost.common.security;

import java.util.Map;
import java.util.Set;

/** Exact existing master-data handler names; HTTP verbs do not determine business actions. */
public final class MasterDataActions {
    private MasterDataActions() {}
    private static final Map<String,Set<String>> DEVELOPERS=Map.of(
        "read",Set.of("listDevelopers","getDeveloper","listDeveloperOptions","listDeveloperAssignments","listDeveloperHistory","listDeveloperMonthlyCosts"),
        "create",Set.of("createDeveloper"),"update",Set.of("updateDeveloper"),
        "delete",Set.of("updateDevelopersDisabled","updateDeveloperEnabled"),"download",Set.of("getDeveloperExport"));
    private static final Map<String,Set<String>> PARTNERS=Map.of(
        "read",Set.of("listPartners","getPartner","listPartnerOptions","listPartnerAssignments","listPartnerHistory","listSalesParts","getSalesPart","listSalesPartOptions"),
        "create",Set.of("createPartner","createSalesPart"),"update",Set.of("updatePartner","updateSalesPartName","saveSalesPartStandard"),
        "delete",Set.of("savePartnerDisable","savePartnerEnable","saveSalesPartDisable","saveSalesPartEnable"),
        "download",Set.of("getPartnerExport","getSalesPartExport"));
    public static String key(String operation) {
        for(var action:DEVELOPERS.entrySet()) if(action.getValue().contains(operation)) return "UI-02."+action.getKey();
        for(var action:PARTNERS.entrySet()) if(action.getValue().contains(operation)) return "UI-15."+action.getKey();
        throw new IllegalArgumentException("기준정보 동작이 아닙니다.");
    }
}
