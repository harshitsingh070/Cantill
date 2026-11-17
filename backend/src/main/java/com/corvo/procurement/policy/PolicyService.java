package com.corvo.procurement.policy;

import com.corvo.procurement.domain.PolicyFact;
import com.corvo.procurement.domain.PolicyFactRepository;
import com.corvo.procurement.domain.PolicyParameter;
import com.corvo.procurement.domain.PolicyParameterRepository;
import com.corvo.procurement.domain.PolicyRule;
import com.corvo.procurement.domain.PolicyRuleRepository;
import com.corvo.procurement.domain.PolicyVersion;
import com.corvo.procurement.domain.PolicyVersionRepository;
import com.corvo.procurement.engine.model.PolicySnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PolicyService {
    private final PolicyVersionRepository versionRepository;
    private final PolicyRuleRepository ruleRepository;
    private final PolicyParameterRepository parameterRepository;
    private final PolicyFactRepository factRepository;

    public PolicyService(PolicyVersionRepository versionRepository,
                         PolicyRuleRepository ruleRepository,
                         PolicyParameterRepository parameterRepository,
                         PolicyFactRepository factRepository) {
        this.versionRepository = versionRepository;
        this.ruleRepository = ruleRepository;
        this.parameterRepository = parameterRepository;
        this.factRepository = factRepository;
    }

    public List<PolicyVersion> versions() {
        return versionRepository.findAll();
    }

    public Optional<PolicyVersion> version(Long id) {
        return versionRepository.findById(id);
    }

    public List<PolicyRule> rules(Long versionId) {
        return ruleRepository.findByVersionIdOrderByPriorityAsc(versionId);
    }

    public List<PolicyParameter> parameters(Long versionId) {
        return parameterRepository.findByVersionId(versionId);
    }

    public List<PolicyFact> facts() {
        return factRepository.findAll();
    }

    public PolicySnapshot snapshot(PolicyVersion version) {
        return PolicyMapper.snapshot(version, rules(version.getId()), parameters(version.getId()), facts());
    }

    public Optional<PolicySnapshot> selectSnapshot(LocalDate requirementDate) {
        return versionRepository
                .findByActivatedAtIsNotNullAndEffectiveFromLessThanEqualOrderByEffectiveFromDescApprovedAtDescIdDesc(requirementDate)
                .stream().findFirst()
                .map(this::snapshot);
    }

    public List<PolicySnapshot> allSnapshots() {
        return versionRepository.findAll().stream()
                .filter(v -> v.getActivatedAt() != null)
                .map(this::snapshot)
                .toList();
    }

    public Long latestActivatedId() {
        return versionRepository.findAll().stream()
                .filter(v -> v.getActivatedAt() != null)
                .map(PolicyVersion::getId)
                .max(Long::compareTo)
                .orElse(0L);
    }

    public String latestActivatedLabel() {
        return versionRepository.findAll().stream()
                .filter(v -> v.getActivatedAt() != null)
                .max(java.util.Comparator.comparing(PolicyVersion::getId))
                .map(PolicyVersion::getLabel)
                .orElse(null);
    }
}
