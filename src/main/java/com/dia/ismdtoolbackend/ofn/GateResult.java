package com.dia.ismdtoolbackend.ofn;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The vocabulary document after the conformance gate, and what the gate removed.
 */
public record GateResult(ObjectNode document, List<ConformanceIssue> issues) {

    public GateResult {
        issues = List.copyOf(issues);
    }

    public List<ConformanceIssue> issuesOfKind(ConformanceIssue.Kind kind) {
        return issues.stream().filter(issue -> issue.kind() == kind).toList();
    }
}
