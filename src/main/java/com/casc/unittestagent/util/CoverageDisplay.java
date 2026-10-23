package com.casc.unittestagent.util;

import java.util.Locale;

/** Never rounds incomplete coverage into a claim of full coverage. */
public final class CoverageDisplay {
    private CoverageDisplay() {}
    public static String percent(double rate) {
        String value = String.format(Locale.ROOT, "%.1f%%", rate * 100d);
        return rate < 1d && "100.0%".equals(value) ? "<100.0%" : value;
    }
}
