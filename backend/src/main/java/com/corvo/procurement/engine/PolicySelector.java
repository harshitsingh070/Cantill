package com.corvo.procurement.engine;

import com.corvo.procurement.engine.model.PolicySnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PolicySelector {
    private PolicySelector() {
    }

    public static Optional<PolicySnapshot> select(List<PolicySnapshot> policies, LocalDate requirementDate) {
        if (policies == null || requirementDate == null) {
            return Optional.empty();
        }
        return policies.stream()
                .filter(p -> p.activatedAt() != null)
                .filter(p -> !p.effectiveFrom().isAfter(requirementDate))
                .sorted(Comparator
                        .comparing(PolicySnapshot::effectiveFrom, Comparator.reverseOrder())
                        .thenComparing(PolicySnapshot::approvedAt, Comparator.nullsFirst(Comparator.reverseOrder()))
                        .thenComparing(PolicySnapshot::id, Comparator.nullsFirst(Comparator.reverseOrder())))
                .findFirst();
    }

    public static Comparator<PolicySnapshot> selectionComparator() {
        return Comparator
                .comparing(PolicySnapshot::effectiveFrom)
                .thenComparing(PolicySnapshot::approvedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(PolicySnapshot::id, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    public static boolean isEligible(PolicySnapshot policy, LocalDate date, LocalDateTime now) {
        return policy.activatedAt() != null && !policy.effectiveFrom().isAfter(date);
    }
}
