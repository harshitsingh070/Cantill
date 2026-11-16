package com.corvo.procurement.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyParameterRepository extends JpaRepository<PolicyParameter, Long> {
    List<PolicyParameter> findByVersionId(Long versionId);

    List<PolicyParameter> findByVersionIdAndParamKey(Long versionId, String paramKey);
}
