package com.casc.unittestagent.model;

/** An exception-reported source location, distinct from JUnit's failing test identity. */
public record RuntimeFailureOrigin(String testId, int sourceLine, String sourceSnippet, String frame) { }
