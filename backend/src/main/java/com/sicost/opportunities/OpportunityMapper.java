package com.sicost.opportunities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.opportunities.OpportunityModels.*;

@Mapper
public interface OpportunityMapper {
    List<Row> list(OpportunityQuery query);
    List<Row> matching(OpportunityQuery query);
    long count(OpportunityQuery query);
    Row row(long id);
    Row lock(long id);
    List<NeedRow> needs(long id);
    List<Approval> approvals(long id);
    List<CostSource> costs(LocalDate today);
    BigDecimal criterion(Criteria criteria);
    Long defaultPrice(String job);
    boolean activeCode(Code code);
    boolean activeCustomer(long id);
    boolean activePart(long id);
    long nextId();
    void insert(Write write);
    void update(Write write);
    void insertNeed(NeedWrite write);
    void updateNeed(NeedWrite write);
    void releaseNeed(long id);
    void retireNeed(long id);
    void appendHistory(History history);
    void requestApproval(Request request);
}
