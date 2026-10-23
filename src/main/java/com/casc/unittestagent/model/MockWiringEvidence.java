package com.casc.unittestagent.model;

/** Source-backed wiring fact, bound to the exact candidate; never populated from model output. */
public record MockWiringEvidence(String category, String testClass, String method, String subject,
                           String receiver, int line, String snippet, String fact,
                           String testHash, String productionHash, String removableAssignment) {}
