package com.sicost.parts;

import org.springframework.stereotype.Component;

@Component
public class PartSettlementApprovalPolicy {
    public boolean approved() { return false; }
}
