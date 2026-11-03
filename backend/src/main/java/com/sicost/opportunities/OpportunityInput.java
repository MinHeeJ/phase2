package com.sicost.opportunities;

import com.fasterxml.jackson.databind.JsonNode;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.sicost.opportunities.OpportunityModels.*;

public final class OpportunityInput {
    private OpportunityInput() {}
    public static ApiException error(String field) { return ApiException.badRequest("입력값을 확인하세요.",field); }
    public static long id(long id) { if(id<1) throw error("id"); return id; }
    public static String month(String s, String field) {
        if(s==null || !s.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw error(field);
        try { YearMonth.parse(s); } catch(DateTimeException e) { throw error(field); } return s;
    }
    public static void only(JsonNode n, Set<String> keys) {
        if(n==null || !n.isObject()) throw error(null);
        n.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw error(k);});
    }
    public static Save parse(JsonNode n) {
        only(n,Set.of("oppt_nm","cust_ptnr_id","bsn_part_id","owner_user_id","bsn_stg_cd","win_prblt_rt","expc_amt","cntrct_ty_cd","bgn_ym","mth_cnt","next_action","next_action_dt","rmrk_desc","needs","amount_difference_reason"));
        String stage=text(n,"bsn_stg_cd",10,false);
        if(stage!=null && !Set.of("DISC","PROP","NEGO","PRE").contains(stage))throw error("bsn_stg_cd");
        String type=text(n,"cntrct_ty_cd",10,true);
        if(!Set.of("TK","MO","DP","IN").contains(type))throw error("cntrct_ty_cd");
        Long customer=number(n,"cust_ptnr_id",1,true), part=number(n,"bsn_part_id",1,false);
        if(!type.equals("IN") && part==null)throw error("bsn_part_id");
        var needs=n.get("needs"); if(needs==null || !needs.isArray())throw error("needs");
        List<NeedInput> rows=new ArrayList<>(); Set<Long> ids=new HashSet<>();
        for(var r:needs) {
            int index=rows.size(); String prefix="needs["+index+"].";
            try {
                only(r,Set.of("need_id","job_cd","grd_cd","psn_cnt","bgn_ym","mth_cnt","mth_mm_qty","expc_untprc_amt","rmrk_desc"));
                Long key=number(r,"need_id",1,false);
                if(key!=null && !ids.add(key))throw error("need_id");
                rows.add(new NeedInput(key,text(r,"job_cd",30,true),text(r,"grd_cd",30,false),
                    integer(r,"psn_cnt",1,999),month(text(r,"bgn_ym",7,true),"bgn_ym"),integer(r,"mth_cnt",1,120),
                    decimal(r,"mth_mm_qty",new BigDecimal("0.01"),BigDecimal.ONE,true),
                    number(r,"expc_untprc_amt",0,false),text(r,"rmrk_desc",200,false)));
            } catch(ApiException e) { throw error(prefix+(e.field()==null?"":e.field())); }
        }
        String date=text(n,"next_action_dt",10,false); LocalDate deadline=null;
        try {if(date!=null)deadline=LocalDate.parse(date);}catch(DateTimeException e){throw error("next_action_dt");}
        return new Save(text(n,"oppt_nm",200,true),customer,part,number(n,"owner_user_id",1,false),stage,
            decimal(n,"win_prblt_rt",BigDecimal.ZERO,new BigDecimal("100"),false),number(n,"expc_amt",0,true),type,
            month(text(n,"bgn_ym",7,true),"bgn_ym"),integer(n,"mth_cnt",1,120),text(n,"next_action",200,false),deadline,
            text(n,"rmrk_desc",Integer.MAX_VALUE,false),List.copyOf(rows),text(n,"amount_difference_reason",Integer.MAX_VALUE,false));
    }
    private static String text(JsonNode n,String key,int max,boolean required) {
        var v=n.get(key);if(v==null||v.isNull()){if(required)throw error(key);return null;}
        if(!v.isTextual())throw error(key);String s=v.textValue().trim();
        if(s.length()>max || required&&s.isEmpty() || key.equals("amount_difference_reason")&&s.isEmpty())throw error(key);
        return s.isEmpty()?null:s;
    }
    private static Long number(JsonNode n,String key,long min,boolean required) {
        var v=n.get(key);if(v==null||v.isNull()){if(required)throw error(key);return null;}
        if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<min)throw error(key);return v.longValue();
    }
    private static int integer(JsonNode n,String key,int min,int max) {
        long v=number(n,key,min,true);if(v>max)throw error(key);return (int)v;
    }
    private static BigDecimal decimal(JsonNode n,String key,BigDecimal min,BigDecimal max,boolean required) {
        var v=n.get(key);if(v==null||v.isNull()){if(required)throw error(key);return null;}
        if(!v.isNumber())throw error(key);var d=v.decimalValue();
        if(d.compareTo(min)<0||d.compareTo(max)>0||d.stripTrailingZeros().scale()>2)throw error(key);return d.setScale(2);
    }
}
