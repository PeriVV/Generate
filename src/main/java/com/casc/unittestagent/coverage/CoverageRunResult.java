package com.casc.unittestagent.coverage;

import com.casc.unittestagent.model.CoverageGap;
import com.casc.unittestagent.model.CoverageSnapshot;

import java.util.ArrayList;
import java.util.List;

/** 覆盖率执行器的单轮结果。 */
public class CoverageRunResult {
    private CoverageSnapshot snapshot = new CoverageSnapshot();
    private List<CoverageGap> gaps = new ArrayList<>();
    private boolean cancelled;
    private boolean environmentError;

    public CoverageSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(CoverageSnapshot snapshot) { this.snapshot = snapshot; }
    public List<CoverageGap> getGaps() { return gaps; }
    public void setGaps(List<CoverageGap> gaps) { this.gaps = gaps == null ? new ArrayList<>() : gaps; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public boolean isEnvironmentError() { return environmentError; }
    public void setEnvironmentError(boolean environmentError) { this.environmentError = environmentError; }
}
