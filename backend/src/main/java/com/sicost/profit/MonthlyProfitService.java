package com.sicost.profit;

import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.billingsops.BillingModels.CostRevenueQuery;
import com.sicost.common.finance.MonthlyProfitReader;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.PermissionService;
import com.sicost.costallocation.CostAllocationService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.profit.MonthlyProfitModels.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class MonthlyProfitService implements MonthlyProfitReader {
    private final MonthlyProfitMapper mapper;
    private final BillingRevenueQuery billing;
    private final CostAllocationService costs;
    private final ExcelExportService excel;
    private final PermissionService permissions;
    private final Clock clock;
    public MonthlyProfitService(MonthlyProfitMapper mapper,BillingRevenueQuery billing,CostAllocationService costs,
            ExcelExportService excel,PermissionService permissions,Clock clock) {
        this.mapper=mapper;this.billing=billing;this.costs=costs;this.excel=excel;this.permissions=permissions;
        this.clock=clock;
    }
    private YearMonth now() {return YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private static Map<String,Object> immutable(Map<String,Object> value) {return Collections.unmodifiableMap(new LinkedHashMap<>(value));}
    public Annual monthly(int year) {
        permissions.can("UI-08.read");
        var current=calculate(year);var previous=year>1?calculate(year-1):List.<Map<String,Object>>of();
        var result=new ArrayList<Map<String,Object>>();
        for(int m=1;m<=12;m++) {
            var row=new LinkedHashMap<>(current.get(m-1));
            row.put("cumulative",sum(current,1,m));row.put("previous",sum(previous,1,m));
            row.put("operating_export_file",workbook(year,m,current,previous,false));
            row.put("net_export_file",workbook(year,m,current,previous,true));result.add(immutable(row));
        }
        return new Annual(year,List.copyOf(result),previous);
    }
    @Override public MonthlyProfitReader.Month read(String month) {
        YearMonth ym;
        try {ym=YearMonth.parse(month);}catch(RuntimeException ex){throw MonthlyProfitInput.invalid("month");}
        if(ym.getYear()<1||ym.getYear()>9999)throw MonthlyProfitInput.invalid("month");
        if(ym.getYear()==2025)return new MonthlyProfitReader.Month(month,null,null,List.of("IMPORT_NOT_AVAILABLE"));
        var row=calculate(ym.getYear()).get(ym.getMonthValue()-1);
        return new MonthlyProfitReader.Month(month,number(row,"revenue"),number(row,"operating_profit"),warnings(row));
    }
    public List<Map<String,Object>> calculate(int year) {
        if(year<1||year>9999)throw MonthlyProfitInput.invalid("year");
        var closed=mapper.closedMonths(year);var result=new ArrayList<Map<String,Object>>();
        List<com.sicost.billingsops.BillingModels.Revenue> revenue=null;
        for(int m=1;m<=12;m++) {
            String month=YearMonth.of(year,m).toString();boolean locked=closed.contains(month);
            var row=new LinkedHashMap<String,Object>();for(String key:FIELDS)row.put(key,null);
            row.put("month",month);row.put("closed",locked);row.put("source",locked?"CLOSE":year==2025?"IMPORT":"LIVE");
            var warning=new ArrayList<String>();
            if(locked||year==2025) {
                var saved=mapper.snapshot(new SnapshotKey(month,locked?"CLOSE":"IMPORT"));
                if(saved==null||saved.isEmpty())warning.add(locked?"MISSING_CLOSED_SNAPSHOT":"MISSING_IMPORT");
                else for(String key:FIELDS)row.put(key,number(saved,key));
            } else if(YearMonth.parse(month).isAfter(now()))warning.add("FUTURE_ACTUAL");
            else {
                if(revenue==null)revenue=billing.revenue(year);
                long sales=0;for(var r:revenue)if(r.month().equals(month))sales=Math.addExact(sales,r.confirmed_supply_amt());
                row.put("revenue",sales);
                var allocation=costs.calculate(month,null);warning.addAll(allocation.warnings());
                long labor=0,idle=0,direct=0,indirect=0,rnd=0,reference=0;
                for(var item:allocation.items()) {
                    switch(item.aloc_ty_cd()) {
                        case "PROJECT" -> {labor=Math.addExact(labor,item.lbr_cst_amt());direct=Math.addExact(direct,item.drct_expns_amt());}
                        case "IDLE" -> {idle=Math.addExact(idle,item.lbr_cst_amt());direct=Math.addExact(direct,item.drct_expns_amt());}
                        case "INDIRECT" -> indirect=Math.addExact(indirect,item.cost());
                        case "RND" -> rnd=Math.addExact(rnd,item.cost());
                        default -> throw new IllegalStateException("알 수 없는 원가 분류");
                    }
                    reference=Math.addExact(reference,item.sls_amt());
                }
                var facts=mapper.facts(month);
                boolean missing=facts==null||facts.payroll_count()==0||allocation.warnings().stream().anyMatch(w->w.contains("급여 미업로드"));
                if(missing)warning.add("MISSING_PAYROLL");
                if(year<now().getYear()&&missing&&revenue.stream().noneMatch(r->r.month().equals(month))) {
                    row.put("revenue",null);warning.add("MISSING_REVENUE_SOURCE");
                }
                if(facts!=null&&facts.draft_count()>0)warning.add("UNCONFIRMED_BILLING_EXCLUDED");
                row.put("project_labor_cost",missing?null:labor);row.put("idle_cost",missing?null:idle);
                row.put("direct_expenses",missing?null:direct);row.put("indirect_labor_cost",missing?null:indirect);
                row.put("rnd_cost",missing?null:rnd);row.put("allocation_revenue_reference",missing?null:reference);
                long sga=0,noi=0,noe=0;
                for(var entry:mapper.entries(month))switch(entry.category()) {
                    case "SGA" -> sga=Math.addExact(sga,entry.amount());case "NOI" -> noi=Math.addExact(noi,entry.amount());
                    case "NOE" -> noe=Math.addExact(noe,entry.amount());default -> throw new IllegalStateException("알 수 없는 재무 분류");
                }
                row.put("sga_input",sga);row.put("non_operating_income",noi);row.put("non_operating_expenses",noe);
                row.put("previous_adjustment",mapper.adjustment(month));row.put("corporate_tax",tax(year,month,closed));
                if(row.get("corporate_tax")==null)warning.add("MISSING_TAX_PLAN");
                row.put("cost",add(row,"project_labor_cost","idle_cost","direct_expenses"));
                row.put("sga",add(row,"sga_input","indirect_labor_cost","rnd_cost"));
                row.put("gross_profit",subtract(number(row,"revenue"),number(row,"cost")));
                row.put("operating_profit",plus(subtract(number(row,"gross_profit"),number(row,"sga")),number(row,"previous_adjustment")));
                row.put("net_profit",subtract(subtract(plus(number(row,"operating_profit"),noi),noe),number(row,"corporate_tax")));
            }
            row.put("operating_margin",ratio(number(row,"operating_profit"),number(row,"revenue")));
            row.put("net_margin",ratio(number(row,"net_profit"),number(row,"revenue")));
            row.put("warnings",List.copyOf(new LinkedHashSet<>(warning)));row.put("warning",String.join("; ",new LinkedHashSet<>(warning)));
            result.add(immutable(row));
        }
        return List.copyOf(result);
    }
    private Long tax(int year,String month,List<String> closed) {
        Long annual=mapper.annualTax(year);if(annual==null)return null;
        long fixed=0;var snapshots=mapper.taxMonths(year);
        for(String locked:closed) {
            var snapshot=snapshots.stream().filter(t->t.month().equals(locked)).findFirst().orElse(null);
            if(snapshot==null)return null;fixed=Math.addExact(fixed,snapshot.amount());
        }
        int count=12-closed.size();if(count==0||annual<fixed)return null;
        long remaining=Math.subtractExact(annual,fixed),base=remaining/count;
        String last=null;for(int m=1;m<=12;m++){String ym=YearMonth.of(year,m).toString();if(!closed.contains(ym))last=ym;}
        return month.equals(last)?Math.addExact(base,remaining%count):base;
    }
    public Map<String,Object> structure(int year,int month,String period) {
        permissions.can("UI-07.read");
        int from=switch(period){case "MONTH"->month;case "QUARTER"->((month-1)/3)*3+1;case "YTD"->1;default->throw MonthlyProfitInput.invalid("period");};
        if(month<1||month>12)throw MonthlyProfitInput.invalid("month");
        var current=calculate(year);var previous=year>1?calculate(year-1):List.<Map<String,Object>>of();
        var row=new LinkedHashMap<>(sum(current,from,month));var before=sum(previous,from,month);
        row.put("year",year);row.put("month",month);row.put("from_month",from);row.put("period",period);row.put("previous",before);
        var differences=new LinkedHashMap<String,Object>();var growth=new LinkedHashMap<String,Object>();
        for(String key:FIELDS){Long delta=subtract(number(row,key),number(before,key));differences.put(key,delta);
            Long denominator=number(before,key);growth.put(key,denominator==null?null:ratio(delta,BigDecimal.valueOf(denominator).abs()));}
        row.put("difference",immutable(differences));row.put("growth_rate",immutable(growth));
        row.put("margin_difference",decimalDifference((BigDecimal)row.get("operating_margin"),(BigDecimal)before.get("operating_margin")));
        var included=current.subList(from-1,month);boolean available=year!=2025&&included.stream().allMatch(r->r.get("cost")!=null);
        row.put("composition_available",available);
        row.put("cost_breakdown",available?costBreakdown(row):List.of());
        var groups=new LinkedHashMap<String,Map<String,long[]>>();var names=new HashMap<String,String>();
        for(String dimension:List.of("CONTRACT_TYPE","CUSTOMER","PART"))groups.put(dimension,new LinkedHashMap<>());
        var warning=new ArrayList<>(warnings(row));
        if(available) {
            var projectRevenue=included.stream().anyMatch(r->!Boolean.TRUE.equals(r.get("closed")))?billing.projectRevenue(new CostRevenueQuery(year,null)):List.<com.sicost.billingsops.BillingModels.ProjectRevenue>of();
            for(var item:included) {
                String ym=(String)item.get("month");
                if(Boolean.TRUE.equals(item.get("closed"))) {
                    var details=mapper.details(ym);
                    for(var d:details)if(groups.containsKey(d.dimension()))accumulate(groups.get(d.dimension()),names,d);
                    for(String dim:groups.keySet())if(details.stream().noneMatch(d->dim.equals(d.dimension())))warning.add("MISSING_CLOSED_COMPOSITION: "+ym+" "+dim);
                } else {
                    var byProject=new LinkedHashMap<Long,long[]>();
                    for(var r:projectRevenue)if(r.month().equals(ym))byProject.computeIfAbsent(r.prj_id(),k->new long[2])[0]=Math.addExact(byProject.get(r.prj_id())[0],r.confirmed_supply_amt());
                    for(var c:costs.calculate(ym,null).items())if("PROJECT".equals(c.aloc_ty_cd())&&c.prj_id()!=null) {
                        var amounts=byProject.computeIfAbsent(c.prj_id(),k->new long[2]);amounts[1]=Math.addExact(amounts[1],c.cost());
                    }
                    for(var p:byProject.entrySet()) {
                        var project=mapper.project(new ProjectMonth(ym,p.getKey()));
                        for(String dim:groups.keySet()) {
                            String code=project==null?null:switch(dim){case "CONTRACT_TYPE"->project.contract();case "CUSTOMER"->project.customer_id()==null?null:project.customer_id().toString();default->project.part_id()==null?null:project.part_id().toString();};
                            String name=project==null?null:switch(dim){case "CONTRACT_TYPE"->project.contract_name();case "CUSTOMER"->project.customer_name();default->project.part_name();};
                            if(code==null)warning.add("MISSING_ATTRIBUTION: "+ym+" "+dim);
                            accumulate(groups.get(dim),names,new Detail(dim,code,name,p.getValue()[0],p.getValue()[1],Math.subtractExact(p.getValue()[0],p.getValue()[1])));
                        }
                    }
                }
            }
        }
        row.put("revenue_by_contract",compositions(groups.get("CONTRACT_TYPE"),names,"CONTRACT_TYPE",number(row,"revenue"),false));
        row.put("revenue_by_customer",compositions(groups.get("CUSTOMER"),names,"CUSTOMER",number(row,"revenue"),true));
        row.put("revenue_by_part",compositions(groups.get("PART"),names,"PART",number(row,"revenue"),false));
        long contribution=0;for(var c:compositions(groups.get("CONTRACT_TYPE"),names,"CONTRACT_TYPE",number(row,"revenue"),false))contribution=Math.addExact(contribution,c.gross_profit());
        row.put("contribution_difference",available?subtract(number(row,"gross_profit"),contribution):null);
        row.put("contribution_note","유형별 기여도는 프로젝트 귀속 매출총이익입니다. 대기 원가·미귀속 경비는 차이로 표시합니다.");
        row.put("months",current);row.put("warnings",List.copyOf(new LinkedHashSet<>(warning)));row.put("warning",String.join("; ",new LinkedHashSet<>(warning)));
        row.put("export_file",structureWorkbook(year,from,month,row));return immutable(row);
    }
    private static void accumulate(Map<String,long[]> group,Map<String,String> names,Detail detail) {
        String key=detail.code()==null?"UNATTRIBUTED":detail.code();names.put(detail.dimension()+":"+key,detail.name()==null?"미귀속":detail.name());
        var pair=group.computeIfAbsent(key,k->new long[2]);pair[0]=Math.addExact(pair[0],detail.revenue());pair[1]=Math.addExact(pair[1],detail.cost());
    }
    private static List<Composition> compositions(Map<String,long[]> values,Map<String,String> names,String dim,Long total,boolean top) {
        var sorted=new ArrayList<>(values.entrySet());sorted.sort((a,b)->{int compare=Long.compare(b.getValue()[0],a.getValue()[0]);if(compare!=0)return compare;try{return Long.compare(Long.parseLong(a.getKey()),Long.parseLong(b.getKey()));}catch(NumberFormatException ex){return a.getKey().compareTo(b.getKey());}});
        var result=new ArrayList<Composition>();long otherRevenue=0,otherCost=0;int index=0;
        for(var entry:sorted){var pair=entry.getValue();if(top&&index++>=5){otherRevenue=Math.addExact(otherRevenue,pair[0]);otherCost=Math.addExact(otherCost,pair[1]);}else result.add(composition(entry.getKey(),names.get(dim+":"+entry.getKey()),pair[0],pair[1],total));}
        if(top&&sorted.size()>5)result.add(composition("OTHER","그 밖",otherRevenue,otherCost,total));return List.copyOf(result);
    }
    private static Composition composition(String code,String name,long revenue,long cost,Long total) {long gross=Math.subtractExact(revenue,cost);return new Composition(code,name,revenue,cost,gross,ratio(revenue,total),ratio(gross,revenue));}
    private static List<Composition> costBreakdown(Map<String,Object> row) {
        var result=new ArrayList<Composition>();for(String key:List.of("project_labor_cost","idle_cost","direct_expenses")){
            Long cost=number(row,key);result.add(new Composition(key,LABELS.get(FIELDS.indexOf(key)),null,cost,null,ratio(cost,number(row,"cost")),null));}
        return List.copyOf(result);
    }
    public static Map<String,Object> sum(List<Map<String,Object>> months,int from,int through) {
        var result=new LinkedHashMap<String,Object>();var selected=months.stream().filter(r->{int m=Integer.parseInt(((String)r.get("month")).substring(5));return m>=from&&m<=through;}).toList();
        for(String key:FIELDS){Long total=selected.isEmpty()?null:0L;for(var r:selected){Long n=number(r,key);if(n==null){total=null;break;}total=Math.addExact(total,n);}result.put(key,total);}
        result.put("operating_margin",ratio(number(result,"operating_profit"),number(result,"revenue")));result.put("net_margin",ratio(number(result,"net_profit"),number(result,"revenue")));
        var warnings=selected.stream().flatMap(r->warnings(r).stream()).distinct().toList();result.put("warnings",warnings);result.put("warning",String.join("; ",warnings));return immutable(result);
    }
    private ExportFile workbook(int year,int through,List<Map<String,Object>> current,List<Map<String,Object>> previous,boolean net) {
        var columns=new ArrayList<ExportColumn<Integer>>();columns.add(new ExportColumn<>("item","항목",false,false,i->LABELS.get(i)));
        for(int m=1;m<=12;m++){final int index=m-1;columns.add(new ExportColumn<>("m"+m,m+"월",true,true,i->number(current.get(index),FIELDS.get(i))));}
        var cumulative=sum(current,1,through);var before=sum(previous,1,through);
        columns.add(new ExportColumn<>("total","누계",true,true,i->number(cumulative,FIELDS.get(i))));columns.add(new ExportColumn<>("previous","전년 동기간",true,true,i->number(before,FIELDS.get(i))));
        columns.add(new ExportColumn<>("difference","차이",true,true,i->subtract(number(cumulative,FIELDS.get(i)),number(before,FIELDS.get(i)))));
        var indices=new ArrayList<Integer>();for(int i=0;i<FIELDS.size();i++)if(net||i<=11)indices.add(i);
        return file((net?"순이익표":"영업이익표")+"-"+year+"-"+through,excel.create(year+"년 1~"+through+"월 · 원","기본 사용자",LocalDateTime.now(clock),columns,indices));
    }
    private ExportFile structureWorkbook(int year,int from,int through,Map<String,Object> row) {
        var columns=List.<ExportColumn<Integer>>of(new ExportColumn<>("item","항목",false,false,i->LABELS.get(i)),new ExportColumn<>("amount","금액(원)",true,true,i->row.get(FIELDS.get(i))));
        return file("수익구조-"+year+"-"+through,excel.create(year+"년 "+from+"~"+through+"월 · 원","기본 사용자",LocalDateTime.now(clock),columns,java.util.stream.IntStream.range(0,FIELDS.size()).boxed().toList()));
    }
    private static ExportFile file(String name,byte[] bytes){return new ExportFile(name+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",Base64.getEncoder().encodeToString(bytes));}
    private static Long number(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:((Number)value).longValue();}
    @SuppressWarnings("unchecked") private static List<String> warnings(Map<String,Object> row){return (List<String>)row.getOrDefault("warnings",List.of());}
    private static Long add(Map<String,Object> row,String... keys){Long total=0L;for(String key:keys)total=plus(total,number(row,key));return total;}
    private static Long plus(Long a,Long b){return a==null||b==null?null:Math.addExact(a,b);}
    private static Long subtract(Long a,Long b){return a==null||b==null?null:Math.subtractExact(a,b);}
    private static BigDecimal ratio(Long a,Long b){return b==null?null:ratio(a,BigDecimal.valueOf(b));}
    private static BigDecimal ratio(Long a,BigDecimal b){return a==null||b==null||b.signum()==0?null:BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(100)).divide(b,1,RoundingMode.HALF_UP);}
    private static BigDecimal decimalDifference(BigDecimal a,BigDecimal b){return a==null||b==null?null:a.subtract(b);}
}
