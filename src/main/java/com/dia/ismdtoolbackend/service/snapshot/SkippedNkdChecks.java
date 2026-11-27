package com.dia.ismdtoolbackend.service.snapshot;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The concepts of the current request whose NKD domain/range check was skipped. Inside a transaction a
 * concept counts once that transaction commits. Outside a web request nothing is collected.
 */
public final class SkippedNkdChecks {

    private static final String ATTRIBUTE = SkippedNkdChecks.class.getName();

    private SkippedNkdChecks() {
    }

    public static void record(Collection<String> conceptIris) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null || conceptIris.isEmpty()) {
            return;
        }
        List<String> iris = List.copyOf(conceptIris);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    add(request, iris);
                }
            });
        } else {
            add(request, iris);
        }
    }

    /** The concepts recorded so far in this request. */
    @SuppressWarnings("unchecked")
    public static List<String> current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return List.of();
        }
        Set<String> iris = (Set<String>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return iris == null ? List.of() : List.copyOf(iris);
    }

    @SuppressWarnings("unchecked")
    private static void add(RequestAttributes request, List<String> conceptIris) {
        Set<String> iris = (Set<String>) request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (iris == null) {
            iris = new LinkedHashSet<>();
            request.setAttribute(ATTRIBUTE, iris, RequestAttributes.SCOPE_REQUEST);
        }
        iris.addAll(conceptIris);
    }
}
