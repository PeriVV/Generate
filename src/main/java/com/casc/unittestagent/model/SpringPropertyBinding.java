package com.casc.unittestagent.model;

import java.util.List;

/** A literal source-declared property contract; requiredKeys only contains placeholders with no default. */
public record SpringPropertyBinding(String owner, String member, String type, String injectionSite, String expression,
                                    List<String> keys, List<String> requiredKeys, boolean spel) {
    public SpringPropertyBinding { keys = List.copyOf(keys); requiredKeys = List.copyOf(requiredKeys); }
}
