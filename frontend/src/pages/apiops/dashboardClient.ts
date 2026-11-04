import { readData } from "../../shared/api";
export { downloadDashboard } from "../dashboardops/dashboardExportClient";

export interface DashboardCard {
  state: "READY" | "MISSING" | "ERROR" | "PARTIAL";
  message: string | null;
  error: { code: string; message: string } | null;
}
export interface DashboardComparison {
  previousAmount: number | null;
  changePercent: number | null;
  isReferenceRate: boolean;
  label: string;
}
export interface DashboardData {
  asOf: string;
  basisMonth: string;
  lastClosedMonth: string | null;
  closeStatus: "OPEN" | "CLOSED";
  kpis: {
    revenue: DashboardCard & {
      basisMonth: string;
      amount: number | null;
      previous: DashboardComparison;
      targetAmount: number | null;
      achievementPercent: number | null;
    };
    operatingProfit: DashboardCard & {
      basisMonth: string;
      amount: number | null;
      marginPercent: number | null;
      previous: DashboardComparison;
    };
    ytdOperatingProfit: DashboardCard & {
      fromMonth: string;
      toMonth: string;
      amount: number | null;
      targetAmount: number | null;
      achievementPercent: number | null;
      closedMonths: string[];
      openMonths: string[];
    };
    utilization: DashboardCard & {
      basisMonth: string;
      confirmedMm: number | null;
      availableMm: number | null;
      utilizationPercent: number | null;
      targetPercent: number | null;
      targetDifferencePp: number | null;
      idleDeveloperCount: number | null;
    };
    receivables: DashboardCard & {
      basisDate: string;
      amount: number | null;
      overdueCount: number | null;
      overdueAmount: number | null;
    };
  };
  actions: DashboardCard & {
    basisDate: string;
    totalKinds: number;
    urgentKinds: number;
    items: {
      type: string;
      severity: "URGENT" | "CAUTION";
      state: "READY" | "UNDETERMINED";
      title: string;
      affectedCount: number | null;
      unit: "CASE" | "PERSON" | "MONTH";
      description: string;
      sourceMonth: string | null;
      withoutNextAssignmentCount: number | null;
      recommendedDeveloperCount: number | null;
    }[];
  };
  closeProgress: DashboardCard & {
    basisMonth: string;
    status: string;
    completedCount: number | null;
    totalCount: number;
    checkedAt: string | null;
    checks: DashboardCheck[];
    incompleteChecks: DashboardCheck[];
  };
  projectAlerts: DashboardCard & {
    basisDate: string;
    items: {
      projectId: number;
      projectName: string;
      severity: string;
      statuses: string[];
      marginPercent: number | null;
      budgetUsagePercent: number | null;
      primaryCostCause: {
        sourceId: number;
        name: string;
        amount: number;
      } | null;
      dataState: string;
    }[];
  };
  revenueCostTrend: DashboardCard & {
    basisMonth: string;
    vatIncluded: false;
    months: {
      month: string;
      closeStatus: string;
      revenue: number | null;
      cost: number | null;
      targetRevenue: number | null;
      actualState: string;
      targetState: string;
    }[];
  };
  workforceForecast: DashboardCard & {
    basisDate: string;
    fromMonth: string;
    toMonth: string;
    assumptions: string[];
    shortageThresholdMm: number;
    months: {
      month: string;
      availableMm: number | null;
      requiredMm: number | null;
      shortageMm: number | null;
      isShortage: boolean | null;
      state: string;
    }[];
  };
  partSummary: DashboardCard & {
    basisMonth: string;
    marginLabel: string;
    items: {
      partId: number | null;
      partName: string;
      classification: string;
      revenue: number | null;
      partMarginPercent: number | null;
      marginState: string;
    }[];
  };
  pipeline: DashboardCard & {
    basisDate: string;
    openOpportunityCount: number | null;
    weightedExpectedRevenue: number | null;
    fromMonth: string;
    toMonth: string;
    startingOpportunityTotal: number | null;
    startingOpportunities: {
      opportunityId: number;
      name: string;
      startDate: string;
      stage: string;
      probabilityPercent: number;
      expectedAmount: number;
      weightedExpectedAmount: number;
    }[];
  };
  cashForecast: DashboardCard & {
    basisDate: string;
    fromMonth: string;
    toMonth: string;
    vatIncluded: true;
    months: {
      month: string;
      expectedFinalBalance: number | null;
      minimumOperatingCash: number | null;
      isShortage: boolean | null;
      state: string;
    }[];
  };
  warnings: {
    section: string;
    code: string;
    message: string;
    basisMonth: string | null;
  }[];
}
export interface DashboardCheck {
  code: string;
  name: string;
  status: string;
  issueCount: number;
  description: string;
}
export const getDashboard = (month?: string, signal?: AbortSignal) =>
  readData<DashboardData>(
    `/api/dashboard${month === undefined ? "" : `?${new URLSearchParams({ month })}`}`,
    signal,
  );
