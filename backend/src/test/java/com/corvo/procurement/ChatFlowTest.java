package com.corvo.procurement;

import com.corvo.procurement.chat.ChatService;
import com.corvo.procurement.domain.BatchRun;
import com.corvo.procurement.domain.BatchRunRepository;
import com.corvo.procurement.domain.PolicyAmendmentRepository;
import com.corvo.procurement.domain.PolicyVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ChatFlowTest {
    @Autowired
    private ChatService chatService;

    @Autowired
    private PolicyAmendmentRepository amendmentRepository;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Autowired
    private BatchRunRepository batchRunRepository;

    @Test
    void policyChangeIsDraftedOnlyAfterConfirmation() {
        long versionsBefore = versionRepository.count();
        Map<String, Object> first = send(null, "from 1 Dec, spot limit is 1 lakh", "Asha", "Procurement");
        assertThat(String.valueOf(first.get("reply"))).contains("2026-12-01").contains("spot_limit");
        assertThat(first.get("intent")).isEqualTo("POLICY");
        assertThat(amendmentRepository.count()).isZero();

        Long sessionId = ((Number) first.get("session_id")).longValue();
        Map<String, Object> second = send(sessionId, "confirm", null, null);
        assertThat(second.get("amendment_id")).isNotNull();
        assertThat(second.get("diff")).isNotNull();
        assertThat(amendmentRepository.count()).isEqualTo(1);
        assertThat(versionRepository.count()).isEqualTo(versionsBefore);

        Map<String, Object> third = send(sessionId, "approve it", null, null);
        assertThat(String.valueOf(third.get("reply"))).containsIgnoringCase("Policy Console");
        assertThat(versionRepository.count()).isEqualTo(versionsBefore);
    }

    @Test
    void policyChangeAcceptsBecomesWording() {
        Map<String, Object> first = send(null, "from 1 Dec, spot limit becomes 1 lakh", "Asha", "Procurement");
        assertThat(String.valueOf(first.get("reply")))
                .contains("2026-12-01")
                .contains("spot_limit")
                .contains("100000");
        assertThat(first.get("intent")).isEqualTo("POLICY");
        assertThat(amendmentRepository.count()).isZero();
    }

    @Test
    void chatCollectsPrSlotsAndSubmits() {
        Map<String, Object> first = send(null, "20 safety goggles at PUN1 by 25 Nov", "Asha", "Maintenance");
        assertThat(first.get("intent")).isEqualTo("PR");
        assertThat(String.valueOf(first.get("reply"))).contains("confirm");
        assertThat(first.get("missing_slots")).asList().isEmpty();

        Long sessionId = ((Number) first.get("session_id")).longValue();
        Map<String, Object> second = send(sessionId, "confirm", null, null);
        assertThat(second.get("pr_id")).isNotNull();

        Map<String, Object> third = send(sessionId, "submit", null, null);
        assertThat(third.get("stage")).isEqualTo("SUBMITTED");
        assertThat(String.valueOf(third.get("reply"))).contains("Run #");
        List<BatchRun> runs = batchRunRepository.findAll();
        assertThat(runs).isNotEmpty();
        BatchRun run = runs.get(runs.size() - 1);
        assertThat(run.getFilename()).isEqualTo("pr-" + second.get("pr_id"));
        assertThat(run.getResultJson()).contains("ITM-1050");
    }

    @Test
    void missingNeedByDateIsRequested() {
        Map<String, Object> first = send(null, "20 safety goggles at PUN1", "Asha", "Maintenance");
        assertThat(first.get("missing_slots")).asList().contains("need_by_date");
        assertThat(String.valueOf(first.get("reply"))).containsIgnoringCase("need-by");
    }

    @Test
    void newItemAsksForEstimatedPrice() {
        Map<String, Object> first = send(null, "1 foobar widget at PUN1 by 25 Nov", "Asha", "Maintenance");
        Long sessionId = ((Number) first.get("session_id")).longValue();
        Map<String, Object> choice = send(sessionId, "new item", null, null);
        assertThat(choice.get("missing_slots")).asList().contains("estimated_unit_price");
        Map<String, Object> priced = send(sessionId, "5000", null, null);
        assertThat(priced.get("missing_slots")).asList().isEmpty();
        assertThat(String.valueOf(priced.get("reply"))).contains("confirm");
    }

    private Map<String, Object> send(Long sessionId, String message, String requester, String department) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (sessionId != null) {
            body.put("session_id", sessionId);
        }
        if (requester != null) {
            body.put("requester", requester);
        }
        if (department != null) {
            body.put("department", department);
        }
        body.put("today", "2026-11-16");
        body.put("message", message);
        return chatService.message(body);
    }
}
