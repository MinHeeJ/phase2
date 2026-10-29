package com.sicost.parts;

import org.springframework.stereotype.Component;
import static com.sicost.parts.PartSettlementModels.*;

@Component
public class PartSettlementApprovalPolicy {
    public boolean approved(Key key, Standard standard) {
        return false;
    }
}
