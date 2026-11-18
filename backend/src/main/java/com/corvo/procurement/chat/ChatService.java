package com.corvo.procurement.chat;

import com.corvo.procurement.domain.ChatMessage;
import com.corvo.procurement.domain.ChatMessageRepository;
import com.corvo.procurement.domain.ChatSession;
import com.corvo.procurement.domain.ChatSessionRepository;
import com.corvo.procurement.domain.PolicyAmendment;
import com.corvo.procurement.engine.EngineDataFactory;
import com.corvo.procurement.engine.ItemMatcher;
import com.corvo.procurement.engine.model.EngineData;
import com.corvo.procurement.engine.model.ItemInfo;
import com.corvo.procurement.engine.model.PolicySnapshot;
import com.corvo.procurement.engine.model.RequirementFacts;
import com.corvo.procurement.ingestion.BatchService;
import com.corvo.procurement.policy.ParameterResolver;
import com.corvo.procurement.policy.PolicyService;
import com.corvo.procurement.policy.amendment.PolicyAmendmentService;
import com.corvo.procurement.policy.amendment.PolicyValidationException;
import com.corvo.procurement.pr.PrService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern ITEM_CODE = Pattern.compile("(?i)\\bITM-\\d{4}\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "(?i)\\b(\\d{1,2})\\s*(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?\\s*(\\d{4})?");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "(?i)\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?\\s+(\\d{1,2})(?:,?\\s*(\\d{4}))?");
    private static final Pattern QTY_UOM = Pattern.compile(
            "(?i)\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s*(nos|pcs|pieces?|ltr|litres?|liters?|kg|mtr|meters?|metres?|job|jobs|box|boxes|cyl|cylinders?|set|sets|roll|rolls|pack|packs|pkt|packets?|ton|tons|mt)\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b(\\d[\\d,]*(?:\\.\\d+)?)\\b");

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final EngineDataFactory engineDataFactory;
    private final PolicyService policyService;
    private final PolicyAmendmentService amendmentService;
    private final PrService prService;
    private final ObjectMapper objectMapper;

    public ChatService(ChatSessionRepository sessionRepository,
                       ChatMessageRepository messageRepository,
                       EngineDataFactory engineDataFactory,
                       PolicyService policyService,
                       PolicyAmendmentService amendmentService,
                       PrService prService,
                       ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.engineDataFactory = engineDataFactory;
        this.policyService = policyService;
        this.amendmentService = amendmentService;
        this.prService = prService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> message(Map<String, Object> body) {
        String text = string(body.get("message"));
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        LocalDate today = body.get("today") == null ? LocalDate.now(ZONE) : LocalDate.parse(string(body.get("today")));
        ChatSession session = resolveSession(body, today);
        ChatState state = readState(session);
        List<String> tools = new ArrayList<>();

        saveMessage(session.getId(), "user", text);
        String lower = text.toLowerCase(Locale.ROOT).trim();
        String reply;
        if (lower.equals("reset") || lower.equals("cancel") || lower.equals("start over")) {
            state = new ChatState();
            reply = "Cleared. Tell me what you need to purchase, or describe a policy change.";
            tools.add("ask_clarification");
        } else if ("POLICY".equals(state.intent)) {
            reply = handlePolicy(text, lower, state, session, today, tools);
        } else if ("PR".equals(state.intent)) {
            reply = handlePr(text, lower, state, session, today, tools);
        } else if (looksLikePolicy(text)) {
            state.intent = "POLICY";
            reply = handlePolicy(text, lower, state, session, today, tools);
        } else {
            state.intent = "PR";
            reply = handlePr(text, lower, state, session, today, tools);
        }

        if (reply == null) {
            reply = "I could not understand that. Could you rephrase?";
            tools.add("ask_clarification");
        }
        saveMessage(session.getId(), "assistant", reply);
        session.setStateJson(write(state));
        sessionRepository.save(session);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session_id", session.getId());
        response.put("session_key", session.getSessionKey());
        response.put("reply", reply);
        response.put("intent", state.intent);
        response.put("stage", state.stage);
        response.put("missing_slots", missingSlots(state));
        response.put("tools", new LinkedHashSet<>(tools));
        response.put("draft", state);
        if (state.prId != null) {
            response.put("pr_id", state.prId);
        }
        if (state.amendmentId != null) {
            response.put("amendment_id", state.amendmentId);
        }
        if (state.policyDiff != null) {
            response.put("diff", state.policyDiff);
        }
        return response;
    }

    public List<ChatMessage> messages(Long sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId);
    }

    private String handlePolicy(String text, String lower, ChatState state, ChatSession session,
                                LocalDate today, List<String> tools) {
        if (state.amendmentId != null) {
            if (lower.contains("approve") || lower.contains("activate")) {
                return "I can draft policy changes, but approval and activation must be done by Procurement in the Policy Console. "
                        + "The draft amendment is #" + state.amendmentId + ".";
            }
            return "Policy amendment #" + state.amendmentId + " is drafted and waiting for Procurement review in the Policy Console.";
        }
        if (state.awaitingConfirm) {
            if (isConfirm(lower)) {
                tools.add("describe_policy_change");
                try {
                    PolicyAmendment amendment = amendmentService.create("chat", state.policyText, null,
                            state.policyEffective, state.policyOps,
                            session.getRequester() == null ? "buyer" : session.getRequester(), true);
                    state.amendmentId = amendment.getId();
                    state.stage = "POLICY_DRAFTED";
                    state.awaitingConfirm = false;
                    try {
                        state.policyDiff = amendmentService.preview(amendment.getId());
                    } catch (PolicyValidationException e) {
                        return "Created draft amendment #" + amendment.getId()
                                + ", but validation failed: " + e.getMessage()
                                + ". Fix it in the manual policy editor.";
                    }
                    return "Draft amendment #" + amendment.getId() + " created. "
                            + state.policyDiff.get("summary")
                            + " Procurement must review and approve it in the Policy Console; I cannot approve or activate it.";
                } catch (PolicyValidationException e) {
                    return "I could not create the draft: " + e.getMessage();
                }
            }
            if (lower.contains("no") || lower.contains("cancel")) {
                state.awaitingConfirm = false;
                return "Okay, the policy change was not created. You can restate it when ready.";
            }
        }

        if (state.policyEffective == null) {
            LocalDate date = parseDate(text, today, true);
            if (date != null) {
                state.policyEffective = date;
            }
        }
        if (state.policyParam == null) {
            state.policyParam = detectPolicyParameter(lower);
        }
        if (state.policyValue == null) {
            state.policyValue = detectPolicyValue(lower);
        }
        if (state.policyScope == null || state.policyScope.isEmpty()) {
            state.policyScope = detectPolicyScope(lower);
        }
        state.policyText = state.policyText == null ? text : state.policyText + " " + text;

        List<String> missing = new ArrayList<>();
        if (state.policyEffective == null) {
            missing.add("effective date (for example, 1 Dec 2026)");
        }
        if (state.policyParam == null) {
            missing.add("the limit or parameter to change");
        }
        if (state.policyValue == null) {
            missing.add("the new value");
        }
        if (!missing.isEmpty()) {
            tools.add("ask_clarification");
            return "I need " + String.join(", ", missing) + ".";
        }

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "set_parameter");
        operation.put("key", state.policyParam);
        operation.put("value", state.policyValue);
        if (state.policyScope != null && !state.policyScope.isEmpty()) {
            operation.put("scope_json", state.policyScope);
        }
        state.policyOps = List.of(operation);
        state.awaitingConfirm = true;
        tools.add("describe_policy_change");

        String scopeText = state.policyScope == null || state.policyScope.isEmpty()
                ? "" : " for " + state.policyScope;
        String previous = currentPolicyValue(state.policyParam, today);
        return "From " + state.policyEffective + ", " + state.policyParam + " changes from "
                + (previous == null ? "its current value" : previous)
                + " to " + plainNumber(state.policyValue) + scopeText + ". "
                + "This is a draft only. Reply \"confirm\" to create the draft amendment; Procurement must approve and activate it.";
    }

    private String handlePr(String text, String lower, ChatState state, ChatSession session,
                            LocalDate today, List<String> tools) {
        if ("SUBMITTED".equals(state.stage)) {
            return "This PR has already been submitted. The run shows the policy decision for each line.";
        }
        if ("DRAFT_CREATED".equals(state.stage) && state.prId != null) {
            if (lower.contains("submit")) {
                tools.add("submit_pr");
                BatchService.BatchResponse response = prService.submit(state.prId);
                state.stage = "SUBMITTED";
                return summarizeRun(response);
            }
            tools.add("create_pr_draft");
            return "PR #" + state.prId + " is saved as a draft. Reply \"submit\" to run the policy engine.";
        }
        if (state.awaitingConfirm && "AWAITING_CONFIRM".equals(state.stage)) {
            if (isConfirm(lower)) {
                tools.add("create_pr_draft");
                state.prId = createPr(state, session, today);
                state.stage = "DRAFT_CREATED";
                state.awaitingConfirm = false;
                return "PR #" + state.prId + " created. Reply \"submit\" to run the policy engine.";
            }
            if (lower.contains("no") || lower.contains("cancel")) {
                state.lines = new ArrayList<>();
                state.stage = "COLLECTING";
                state.awaitingConfirm = false;
                return "Okay, the PR draft was cleared. Tell me what you need.";
            }
        }

        String awaitingAtStart = state.awaiting;
        if ("item_choice".equals(state.awaiting)) {
            if (lower.contains("new")) {
                state.lines.get(state.activeLine).newItem = true;
                state.lines.get(state.activeLine).itemCode = null;
                state.candidates = List.of();
            } else {
                String chosen = chooseCandidate(lower, state.candidates);
                if (chosen != null) {
                    state.lines.get(state.activeLine).itemCode = chosen;
                    state.lines.get(state.activeLine).description = itemDescription(chosen);
                    state.candidates = List.of();
                }
            }
            state.awaiting = null;
        }

        List<LineDraft> parsed = parseLines(text, today, tools);
        boolean slotAnswer = awaitingAtStart != null;
        if (!parsed.isEmpty() && (!slotAnswer || state.lines.isEmpty())) {
            if (state.lines.isEmpty()) {
                state.lines = parsed;
            } else if ("COLLECTING".equals(state.stage)) {
                state.lines = parsed;
            }
        }
        if (state.lines.isEmpty()) {
            tools.add("ask_clarification");
            return "What item do you need, and how many? For example: \"20 safety goggles at PUN1 by 25 Nov\".";
        }

        fillSlotsFromMessage(text, lower, state, today, tools);

        for (int i = 0; i < state.lines.size(); i++) {
            LineDraft line = state.lines.get(i);
            if (line.itemCode == null && !line.newItem && line.description != null) {
                tools.add("resolve_item");
                resolveItem(line, state);
            }
            if (line.itemCode != null && line.uom == null) {
                line.uom = itemUom(line.itemCode);
            }
        }
        LineDraft active = state.lines.get(state.activeLine >= state.lines.size() ? 0 : state.activeLine);
        String missing = firstMissing(active, state);
        if (missing != null) {
            state.stage = "COLLECTING";
            state.awaiting = missing;
            tools.add("ask_clarification");
            if ("item_choice".equals(missing)) {
                return "I could not match that item confidently. Candidates: "
                        + candidateText(state.candidates) + ". Reply with a code, a number, or \"new item\".";
            }
            if ("plant".equals(missing)) {
                return "Which plant is this for: PUN1 (Pune) or CHK1 (Chakan)?";
            }
            if ("quantity".equals(missing)) {
                return "What quantity do you need?";
            }
            if ("need_by_date".equals(missing)) {
                return "What is the need-by date?";
            }
            if ("estimated_unit_price".equals(missing)) {
                return "This looks like a new item. What is the estimated unit price in INR?";
            }
            return "I need " + missing + ".";
        }

        state.stage = "AWAITING_CONFIRM";
        state.awaiting = null;
        state.awaitingConfirm = true;
        tools.add("ask_clarification");
        return summary(state, session) + " Reply \"confirm\" to create the PR draft.";
    }

    private void fillSlotsFromMessage(String text, String lower, ChatState state, LocalDate today, List<String> tools) {
        LineDraft line = state.lines.get(state.activeLine >= state.lines.size() ? 0 : state.activeLine);
        String awaiting = state.awaiting;
        if ("quantity".equals(awaiting) && line.quantity == null) {
            BigDecimal qty = detectQuantity(text);
            if (qty != null) {
                line.quantity = qty;
            }
        }
        if ("plant".equals(awaiting) && line.plant == null) {
            String plant = detectPlant(lower);
            if (plant != null) {
                line.plant = plant;
            }
        }
        if ("need_by_date".equals(awaiting) && line.needByDate == null) {
            LocalDate date = parseDate(text, today, false);
            if (date != null) {
                line.needByDate = date;
            }
        }
        if ("estimated_unit_price".equals(awaiting) && line.estimatedUnitPrice == null) {
            BigDecimal price = detectPrice(text);
            if (price != null) {
                line.estimatedUnitPrice = price;
            }
        }
        if (line.plant == null) {
            line.plant = detectPlant(lower);
        }
        if (line.needByDate == null) {
            LocalDate date = parseDate(text, today, false);
            if (date != null) {
                line.needByDate = date;
            }
        }
        if (line.urgency == null || "Normal".equals(line.urgency)) {
            if (lower.contains("emergency") || lower.contains("urgent") || lower.contains("asap")) {
                line.urgency = "Emergency";
            } else if (line.urgency == null) {
                line.urgency = "Normal";
            }
        }
    }

    private void resolveItem(LineDraft line, ChatState state) {
        EngineData data = engineDataFactory.build();
        ItemMatcher matcher = new ItemMatcher(new ArrayList<>(data.items().values()), data.aliases());
        BigDecimal threshold = new BigDecimal("0.50");
        BigDecimal tokenWeight = new BigDecimal("0.50");
        BigDecimal fuzzyWeight = new BigDecimal("0.50");
        PolicySnapshot snapshot = policyService.selectSnapshot(LocalDate.now(ZONE)).orElse(null);
        if (snapshot != null) {
            ParameterResolver resolver = new ParameterResolver(snapshot.parameters());
            RequirementFacts facts = new RequirementFacts(null, null, null, null, null, null, null,
                    null, null, false, false, false, false);
            try {
                threshold = resolver.resolveDecimal("item_match_threshold", facts);
                tokenWeight = resolver.resolveDecimal("item_match_token_weight", facts);
                fuzzyWeight = resolver.resolveDecimal("item_match_fuzzy_weight", facts);
            } catch (RuntimeException ignored) {
                // fall back to defaults
            }
        }
        ItemMatcher.MatchResult result = matcher.match(line.description, threshold, tokenWeight, fuzzyWeight);
        if (!"NEW".equals(result.itemCode())) {
            line.itemCode = result.itemCode();
            line.description = itemDescription(result.itemCode());
            line.matchScore = result.score();
            state.candidates = result.candidates();
        } else {
            line.itemCode = null;
            line.matchScore = result.score();
            state.candidates = result.candidates();
            if (!result.candidates().isEmpty()) {
                state.awaiting = "item_choice";
            } else {
                line.newItem = true;
            }
        }
    }

    private String firstMissing(LineDraft line, ChatState state) {
        if (line.itemCode == null && !line.newItem) {
            return "item_choice".equals(state.awaiting) || state.candidates != null && !state.candidates.isEmpty()
                    ? "item_choice" : "item";
        }
        if (line.quantity == null) {
            return "quantity";
        }
        if (line.plant == null) {
            return "plant";
        }
        if (line.needByDate == null) {
            return "need_by_date";
        }
        if (line.newItem && line.estimatedUnitPrice == null) {
            return "estimated_unit_price";
        }
        return null;
    }

    private String summary(ChatState state, ChatSession session) {
        StringBuilder builder = new StringBuilder("Please confirm this PR for ")
                .append(session.getRequester() == null ? "the requester" : session.getRequester());
        if (session.getDepartment() != null) {
            builder.append(" (").append(session.getDepartment()).append(")");
        }
        builder.append(":");
        for (LineDraft line : state.lines) {
            builder.append("\n- ");
            builder.append(plainNumber(line.quantity)).append(" ").append(line.uom == null ? "" : line.uom).append(" ");
            builder.append(line.itemCode == null ? line.description : line.itemCode + " - " + itemDescription(line.itemCode));
            builder.append(" at ").append(line.plant);
            builder.append(", need by ").append(line.needByDate);
            builder.append(", ").append(line.urgency == null ? "Normal" : line.urgency);
            if (line.newItem && line.estimatedUnitPrice != null) {
                builder.append(", estimated ").append(plainNumber(line.estimatedUnitPrice)).append(" INR");
            }
        }
        return builder.toString();
    }

    private String summarizeRun(BatchService.BatchResponse response) {
        Map<String, Object> result = toMap(response.result());
        List<?> lines = result.get("lines") instanceof List<?> list ? list : List.of();
        StringBuilder builder = new StringBuilder("Submitted. Run #" + response.runId()
                + " used policy " + response.policyVersionLabel() + ".");
        int count = 0;
        for (Object value : lines) {
            if (!(value instanceof Map<?, ?> line)) {
                continue;
            }
            if (count++ >= 5) {
                builder.append("\n- ...");
                break;
            }
            builder.append("\n- ").append(line.get("prNo")).append(" line ").append(line.get("prLine"))
                    .append(": ").append(line.get("route"));
            if (line.get("supplierId") != null) {
                builder.append(" to ").append(line.get("supplierId"));
            }
            if (line.get("poId") != null) {
                builder.append(", ").append(line.get("poId"));
            }
            if (line.get("reason") != null) {
                builder.append(" (").append(line.get("reason")).append(")");
            }
        }
        return builder.toString();
    }

    private Long createPr(ChatState state, ChatSession session, LocalDate today) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (LineDraft line : state.lines) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plant", line.plant);
            body.put("item_code", line.itemCode);
            body.put("description", line.description);
            body.put("quantity", line.quantity);
            body.put("uom", line.uom);
            body.put("need_by_date", line.needByDate == null ? null : line.needByDate.toString());
            body.put("urgency", line.urgency == null ? "Normal" : line.urgency);
            body.put("estimated_unit_price", line.estimatedUnitPrice);
            lines.add(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requester", session.getRequester());
        body.put("department", session.getDepartment());
        body.put("lines", lines);
        Map<String, Object> pr = prService.create(body);
        return (Long) pr.get("id");
    }

    private List<LineDraft> parseLines(String text, LocalDate today, List<String> tools) {
        List<String> chunks = splitChunks(text);
        List<LineDraft> lines = new ArrayList<>();
        for (String chunk : chunks) {
            LineDraft line = new LineDraft();
            BigDecimal quantity = detectQuantity(chunk);
            if (quantity == null && chunks.size() > 1) {
                continue;
            }
            line.quantity = quantity;
            line.uom = detectUom(chunk);
            line.plant = detectPlant(chunk.toLowerCase(Locale.ROOT));
            line.needByDate = parseDate(chunk, today, false);
            line.urgency = chunk.toLowerCase(Locale.ROOT).contains("emergency")
                    || chunk.toLowerCase(Locale.ROOT).contains("urgent") ? "Emergency" : "Normal";
            Matcher code = ITEM_CODE.matcher(chunk);
            if (code.find()) {
                line.itemCode = code.group().toUpperCase(Locale.ROOT);
                line.description = itemDescription(line.itemCode);
            } else {
                line.description = cleanDescription(chunk);
            }
            if (line.description != null && line.description.toLowerCase(Locale.ROOT).contains("new item")) {
                line.newItem = true;
                line.description = line.description.replaceAll("(?i)new item", "").trim();
            }
            if (line.quantity == null && (line.description == null || line.description.isBlank())) {
                continue;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            LineDraft line = new LineDraft();
            line.description = cleanDescription(text);
            if (line.description != null && !line.description.isBlank()) {
                lines.add(line);
            }
        }
        if (lines.size() > 1) {
            tools.add("create_pr_draft");
        }
        return lines;
    }

    private List<String> splitChunks(String text) {
        List<String> chunks = new ArrayList<>();
        String[] bySemicolon = text.split(";");
        if (bySemicolon.length > 1) {
            for (String chunk : bySemicolon) {
                if (!chunk.isBlank()) {
                    chunks.add(chunk.trim());
                }
            }
            return chunks;
        }
        Matcher matcher = QTY_UOM.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        if (count > 1) {
            String[] byAnd = text.split("(?i)\\s+and\\s+");
            if (byAnd.length > 1) {
                for (String chunk : byAnd) {
                    if (!chunk.isBlank()) {
                        chunks.add(chunk.trim());
                    }
                }
                return chunks;
            }
        }
        chunks.add(text.trim());
        return chunks;
    }

    private BigDecimal detectQuantity(String text) {
        Matcher matcher = QTY_UOM.matcher(text);
        if (matcher.find()) {
            return decimal(matcher.group(1));
        }
        Matcher number = NUMBER.matcher(text);
        while (number.find()) {
            BigDecimal value = decimal(number.group(1));
            if (value != null && value.compareTo(new BigDecimal("1000")) >= 0
                    && value.compareTo(new BigDecimal("2100")) <= 0 && value.stripTrailingZeros().scale() <= 0) {
                continue;
            }
            return value;
        }
        return null;
    }

    private String detectUom(String text) {
        Matcher matcher = QTY_UOM.matcher(text);
        if (matcher.find()) {
            String raw = matcher.group(2).toLowerCase(Locale.ROOT);
            return switch (raw) {
                case "nos", "pcs", "piece", "pieces" -> "NOS";
                case "ltr", "litre", "litres", "liter", "liters" -> "LTR";
                case "kg" -> "KG";
                case "mtr", "meter", "meters", "metre", "metres" -> "MTR";
                case "job", "jobs" -> "JOB";
                case "box", "boxes" -> "BOX";
                case "cyl", "cylinder", "cylinders" -> "CYL";
                case "set", "sets" -> "SET";
                case "roll", "rolls" -> "ROLL";
                case "pack", "packs", "pkt", "packet", "packets" -> "PACK";
                case "ton", "tons", "mt" -> "TON";
                default -> raw.toUpperCase(Locale.ROOT);
            };
        }
        return null;
    }

    private String cleanDescription(String text) {
        String cleaned = text;
        cleaned = ISO_DATE.matcher(cleaned).replaceAll(" ");
        cleaned = DAY_MONTH.matcher(cleaned).replaceAll(" ");
        cleaned = MONTH_DAY.matcher(cleaned).replaceAll(" ");
        cleaned = QTY_UOM.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("(?i)\\b(by|need|needed|please|raise|create|add|order|buy|purchase|request|pr|for|at|to|of|the|a|an)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(PUN1|CHK1|pune|bhosari|chakan|emergency|urgent|asap)\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(today|tomorrow|next\\s+\\w+day)\\b", " ");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9&%./ -]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String detectPlant(String lower) {
        if (lower.matches(".*\\bpun1\\b.*") || lower.contains("pune") || lower.contains("bhosari")) {
            return "PUN1";
        }
        if (lower.matches(".*\\bchk1\\b.*") || lower.contains("chakan")) {
            return "CHK1";
        }
        return null;
    }

    private BigDecimal detectPrice(String text) {
        Matcher matcher = NUMBER.matcher(text);
        if (matcher.find()) {
            return decimal(matcher.group(1));
        }
        return null;
    }

    private LocalDate parseDate(String text, LocalDate today, boolean policy) {
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher iso = ISO_DATE.matcher(text);
        if (iso.find()) {
            return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        if (lower.contains("today")) {
            return today;
        }
        if (lower.contains("tomorrow")) {
            return today.plusDays(1);
        }
        Matcher inDays = Pattern.compile("(?i)\\bin\\s+(\\d{1,3})\\s+days?").matcher(text);
        if (inDays.find()) {
            return today.plusDays(Integer.parseInt(inDays.group(1)));
        }
        Matcher nextWeekday = Pattern.compile("(?i)\\bnext\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b").matcher(text);
        if (nextWeekday.find()) {
            DayOfWeek day = DayOfWeek.valueOf(nextWeekday.group(1).toUpperCase(Locale.ROOT));
            return today.with(TemporalAdjusters.next(day));
        }
        Matcher dayMonth = DAY_MONTH.matcher(text);
        if (dayMonth.find()) {
            int day = Integer.parseInt(dayMonth.group(1));
            int month = month(dayMonth.group(2));
            int year = dayMonth.group(3) == null ? defaultYear(today, month, day, policy) : Integer.parseInt(dayMonth.group(3));
            return safeDate(year, month, day);
        }
        Matcher monthDay = MONTH_DAY.matcher(text);
        if (monthDay.find()) {
            int month = month(monthDay.group(1));
            int day = Integer.parseInt(monthDay.group(2));
            int year = monthDay.group(3) == null ? defaultYear(today, month, day, policy) : Integer.parseInt(monthDay.group(3));
            return safeDate(year, month, day);
        }
        return null;
    }

    private int defaultYear(LocalDate today, int month, int day, boolean policy) {
        LocalDate candidate = safeDate(today.getYear(), month, day);
        if (candidate == null) {
            return today.getYear();
        }
        if (policy) {
            return candidate.isBefore(today) ? today.getYear() + 1 : today.getYear();
        }
        return candidate.isBefore(today) ? today.getYear() + 1 : today.getYear();
    }

    private LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int month(String raw) {
        String value = raw.toLowerCase(Locale.ROOT).substring(0, 3);
        return switch (value) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> throw new IllegalArgumentException("Unknown month " + raw);
        };
    }

    private boolean looksLikePolicy(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        boolean limitWord = lower.contains("limit") || lower.contains("boundary") || lower.contains("window")
                || lower.contains("consolidation");
        boolean policyWord = lower.contains("policy") || lower.contains("effective") || lower.contains("amend");
        return limitWord || policyWord;
    }

    private String detectPolicyParameter(String lower) {
        if (lower.contains("emergency")) {
            return "emergency_limit";
        }
        if (lower.contains("spot")) {
            return "spot_limit";
        }
        if (lower.contains("rfq") || lower.contains("boundary")) {
            return "rfq_rfp_boundary";
        }
        if (lower.contains("club")) {
            return "club_window_days";
        }
        if (lower.contains("need by") || lower.contains("need-by")) {
            return "need_by_window_days";
        }
        if (lower.contains("consolidation")) {
            return "consolidation_hours";
        }
        return null;
    }

    private BigDecimal detectPolicyValue(String lower) {
        Matcher matcher = Pattern.compile(
                "(?i)(?:limit|boundary|window|consolidation|hours|days)\\s*(?:is|to|at|of|=|:)?\\s*(?:₹|rs\\.?|inr)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(lakhs?|lac|crore|cr|k)?")
                .matcher(lower);
        if (matcher.find()) {
            return scaleValue(matcher.group(1), matcher.group(2));
        }
        Matcher change = Pattern.compile(
                "(?i)(?:should\\s+be|be|is|to|at|of|=|:)\\s*(?:₹|rs\\.?|inr)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(lakhs?|lac|crore|cr|k)?")
                .matcher(lower);
        if (change.find()) {
            return scaleValue(change.group(1), change.group(2));
        }
        Matcher any = Pattern.compile("(?i)(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(lakhs?|lac|crore|cr|k)?")
                .matcher(lower);
        if (any.find()) {
            return scaleValue(any.group(1), any.group(2));
        }
        return null;
    }

    private BigDecimal scaleValue(String raw, String unit) {
        BigDecimal value = decimal(raw);
        if (value == null) {
            return null;
        }
        if (unit == null) {
            return value;
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "lakh", "lakhs", "lac" -> value.multiply(new BigDecimal("100000"));
            case "crore", "cr" -> value.multiply(new BigDecimal("10000000"));
            case "k" -> value.multiply(new BigDecimal("1000"));
            default -> value;
        };
    }

    private Map<String, Object> detectPolicyScope(String lower) {
        Map<String, Object> scope = new LinkedHashMap<>();
        Matcher code = ITEM_CODE.matcher(lower);
        if (code.find()) {
            scope.put("item_code", code.group().toUpperCase(Locale.ROOT));
            return scope;
        }
        String plant = detectPlant(lower);
        if (plant != null) {
            scope.put("plant", plant);
        }
        EngineData data = engineDataFactory.build();
        String category = null;
        for (ItemInfo item : data.items().values()) {
            if (item.category() == null) {
                continue;
            }
            String normalized = item.category().toLowerCase(Locale.ROOT);
            if (lower.contains(normalized) && (category == null || normalized.length() > category.length())) {
                category = item.category();
            }
        }
        if (category != null) {
            scope.put("category", category);
        }
        return scope;
    }

    private String chooseCandidate(String lower, List<String> candidates) {
        if (candidates == null) {
            return null;
        }
        Matcher code = Pattern.compile("(?i)\\bITM-\\d{4}\\b").matcher(lower);
        if (code.find()) {
            return code.group().toUpperCase(Locale.ROOT);
        }
        Matcher number = Pattern.compile("\\b(\\d{1,2})\\b").matcher(lower);
        if (number.find()) {
            int index = Integer.parseInt(number.group(1)) - 1;
            if (index >= 0 && index < candidates.size()) {
                return candidates.get(index);
            }
        }
        for (String candidate : candidates) {
            if (lower.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }

    private String candidateText(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(i + 1).append(". ").append(candidates.get(i))
                    .append(" ").append(itemDescription(candidates.get(i)));
        }
        return builder.toString();
    }

    private String itemDescription(String itemCode) {
        EngineData data = engineDataFactory.build();
        ItemInfo item = data.items().get(itemCode);
        return item == null ? itemCode : item.description();
    }

    private String itemUom(String itemCode) {
        EngineData data = engineDataFactory.build();
        ItemInfo item = data.items().get(itemCode);
        return item == null ? null : item.uom();
    }

    private List<String> missingSlots(ChatState state) {
        List<String> missing = new ArrayList<>();
        if (!"PR".equals(state.intent) || state.lines == null || state.lines.isEmpty()) {
            return missing;
        }
        LineDraft line = state.lines.get(0);
        if (line.itemCode == null && !line.newItem) {
            missing.add("item");
        }
        if (line.quantity == null) {
            missing.add("quantity");
        }
        if (line.plant == null) {
            missing.add("plant");
        }
        if (line.needByDate == null) {
            missing.add("need_by_date");
        }
        if (line.newItem && line.estimatedUnitPrice == null) {
            missing.add("estimated_unit_price");
        }
        return missing;
    }

    private boolean isConfirm(String lower) {
        return lower.equals("confirm") || lower.equals("yes") || lower.equals("y") || lower.equals("ok")
                || lower.equals("okay") || lower.startsWith("confirm ");
    }

    private ChatSession resolveSession(Map<String, Object> body, LocalDate today) {
        if (body.get("session_id") != null) {
            Long id = Long.valueOf(String.valueOf(body.get("session_id")));
            return sessionRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Chat session not found: " + id));
        }
        String key = string(body.get("session_key"));
        if (key != null) {
            return sessionRepository.findBySessionKey(key).orElseGet(() -> createSession(key, body, today));
        }
        return createSession("chat-" + UUID.randomUUID(), body, today);
    }

    private ChatSession createSession(String key, Map<String, Object> body, LocalDate today) {
        ChatSession session = new ChatSession();
        session.setSessionKey(key);
        session.setRequester(body.get("requester") == null ? "buyer" : string(body.get("requester")));
        session.setDepartment(body.get("department") == null ? "Procurement" : string(body.get("department")));
        session.setCreatedAt(LocalDateTime.now(ZONE));
        return sessionRepository.saveAndFlush(session);
    }

    private void saveMessage(Long sessionId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now(ZONE));
        messageRepository.save(message);
    }

    private ChatState readState(ChatSession session) {
        if (session.getStateJson() == null || session.getStateJson().isBlank()) {
            return new ChatState();
        }
        try {
            return objectMapper.readValue(session.getStateJson(), ChatState.class);
        } catch (Exception e) {
            return new ChatState();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize chat state", e);
        }
    }

    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal decimal(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String currentPolicyValue(String key, LocalDate today) {
        if (key == null) {
            return null;
        }
        return policyService.selectSnapshot(today)
                .flatMap(snapshot -> snapshot.parameters().stream()
                        .filter(parameter -> key.equals(parameter.key()))
                        .filter(parameter -> parameter.scopeJson() == null || parameter.scopeJson().isBlank())
                        .findFirst())
                .map(parameter -> parameter.value() instanceof BigDecimal decimal
                        ? decimal.setScale(0, RoundingMode.HALF_UP).toPlainString()
                        : String.valueOf(parameter.value()))
                .orElse(null);
    }

    private String plainNumber(BigDecimal value) {
        return value == null ? "" : value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    public static class ChatState {
        public String intent;
        public String stage = "COLLECTING";
        public List<LineDraft> lines = new ArrayList<>();
        public int activeLine;
        public String awaiting;
        public List<String> candidates = new ArrayList<>();
        public boolean awaitingConfirm;
        public Long prId;
        public Long amendmentId;
        public String policyText;
        public LocalDate policyEffective;
        public String policyParam;
        public BigDecimal policyValue;
        public Map<String, Object> policyScope = new LinkedHashMap<>();
        public List<Map<String, Object>> policyOps = new ArrayList<>();
        public Map<String, Object> policyDiff;
    }

    public static class LineDraft {
        public String itemCode;
        public String description;
        public BigDecimal quantity;
        public String uom;
        public String plant;
        public LocalDate needByDate;
        public String urgency;
        public BigDecimal estimatedUnitPrice;
        public boolean newItem;
        public BigDecimal matchScore;
    }
}
