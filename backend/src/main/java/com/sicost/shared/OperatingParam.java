package com.sicost.shared;

import java.math.BigDecimal;

public record OperatingParam(String oprt_std_id, String aply_bgn_ym, String oprt_std_nm,
        BigDecimal num_val, String txt_val, String unit_nm, String use_plc_desc) {}
