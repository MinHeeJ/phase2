package com.sicost.billingsops;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.billingsops.BillingModels.*;

@Mapper
@org.springframework.stereotype.Repository("billingOperationsMapper")
public interface BillingMapper {
    Project project(long id);
    Long lockProject(long id);
    Stage stage(long id);
    List<Source> sources(Scope scope);
    BigDecimal vatRate(String month);
    String lastClosedMonth();
    String monthStatus(String month);
    List<Project> gapProjects(String month);
    List<Confirmed> confirmed(Scope scope);
    List<State> states(int year);
    State state(long id);
    Long lockBill(long id);
    Balance balance(long id);
    long receiptCount(long id);
    long activeAmendments(long id);
    List<Line> lines(long id);
    List<Receipt> receipts(long id);
    List<State> amendments(long id);
    List<History> history(long id);
    List<Sent> sent(long id);
    List<Revenue> revenue(int year);
    List<Revenue> projectRevenue(Scope scope);
    List<BillingRevenueQuery.ProjectRevenue> strictProjectRevenue(Scope scope);
    List<Cash> cash(int year);
    long planned(int year);
    void lockNumber(String prefix);
    long maximumNumber(String prefix);
    long insertAmendment(AmendmentWrite write);
    int transition(Transition write);
    void audit(Audit audit);
    void download(BillingExportController.Download download);
}
