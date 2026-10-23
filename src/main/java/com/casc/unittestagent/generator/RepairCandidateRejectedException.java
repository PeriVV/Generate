package com.casc.unittestagent.generator;

/** A model candidate contract violation, never a diagnosis of the executed test. */
public class RepairCandidateRejectedException extends IllegalArgumentException {
    public enum Stage { PATCH_SHAPE, REPAIR_SCOPE, ORACLE_CONTRACT }
    private final Stage stage;
    public RepairCandidateRejectedException(Stage stage, String message) {
        super(message);
        this.stage = stage;
    }
    public Stage getStage() { return stage; }
}
