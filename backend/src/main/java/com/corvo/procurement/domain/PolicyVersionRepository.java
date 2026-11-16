package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {
    Optional<PolicyVersion> findByLabel(String label);

    List<PolicyVersion> findByActivatedAtIsNotNullAndEffectiveFromLessThanEqualOrderByEffectiveFromDescApprovedAtDescIdDesc(LocalDate requirementDate);
}
