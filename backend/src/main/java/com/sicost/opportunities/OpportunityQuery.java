package com.sicost.opportunities;

import java.util.*;

public record OpportunityQuery(String q,String status,String stage,Long customer,Long part,
        String from,String to,String order,int page,int size,long offset) {
    public static OpportunityQuery of(Map<String,String> p) {
        Set<String> keys=Set.of("q","oppt_stts_cd","bsn_stg_cd","cust_ptnr_id","bsn_part_id","from","to","sort","page","size");
        p.keySet().forEach(k->{if(!keys.contains(k))throw OpportunityInput.error(k);});
        String status=p.getOrDefault("oppt_stts_cd","OPEN"),stage=p.get("bsn_stg_cd");
        if(!Set.of("OPEN","WON","LOST").contains(status))throw OpportunityInput.error("oppt_stts_cd");
        if(stage!=null&&!Set.of("DISC","PROP","NEGO","PRE").contains(stage))throw OpportunityInput.error("bsn_stg_cd");
        String from=p.get("from"),to=p.get("to");
        if(from!=null)OpportunityInput.month(from,"from");if(to!=null)OpportunityInput.month(to,"to");
        if(from!=null&&to!=null&&from.compareTo(to)>0)throw OpportunityInput.error("to");
        String[] sort=p.getOrDefault("sort","oppt_id,asc").split(",",-1);
        Set<String> columns=Set.of("oppt_id","oppt_no","oppt_nm","cust_ptnr_id","bsn_part_id","bsn_stg_cd","win_prblt_rt","expc_amt","cntrct_ty_cd","bgn_ym","mth_cnt","next_action_dt","updated_at","created_at","oppt_stts_cd");
        if(sort.length!=2||!columns.contains(sort[0])||!Set.of("asc","desc").contains(sort[1]))throw OpportunityInput.error("sort");
        int page=integer(p,"page",0),size=integer(p,"size",50);
        if(page<0)throw OpportunityInput.error("page");if(!Set.of(20,50,100).contains(size))throw OpportunityInput.error("size");
        String q=p.get("q");if(q!=null&&q.isBlank())q=null;
        return new OpportunityQuery(q,status,stage,positive(p,"cust_ptnr_id"),positive(p,"bsn_part_id"),from,to,
            "\""+sort[0]+"\" "+sort[1]+" NULLS LAST, oppt_id ASC",page,size,(long)page*size);
    }
    private static int integer(Map<String,String> p,String key,int fallback) {
        try{return p.containsKey(key)?Integer.parseInt(p.get(key)):fallback;}catch(NumberFormatException e){throw OpportunityInput.error(key);}
    }
    private static Long positive(Map<String,String> p,String key) {
        if(!p.containsKey(key))return null;
        try{long v=Long.parseLong(p.get(key));if(v<1)throw OpportunityInput.error(key);return v;}catch(NumberFormatException e){throw OpportunityInput.error(key);}
    }
}
