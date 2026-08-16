package com.casc.unittestagent.coverage;

/** 覆盖率后台任务的轻量进度回调。 */
@FunctionalInterface
public interface CoverageProgressListener {
    void update(String text, double fraction);

    CoverageProgressListener NONE = (text, fraction) -> { };
}
