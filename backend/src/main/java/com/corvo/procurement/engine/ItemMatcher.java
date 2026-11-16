package com.corvo.procurement.engine;

import com.corvo.procurement.engine.model.AliasInfo;
import com.corvo.procurement.engine.model.ItemInfo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ItemMatcher {
    private static final Set<String> NOISE = Set.of(
            "urgent", "as", "per", "sample", "for", "maintenance", "repeat",
            "please", "need", "required", "new", "old", "the", "and", "with"
    );

    private final List<ItemInfo> items;
    private final Map<String, String> aliases;

    public ItemMatcher(List<ItemInfo> items, List<AliasInfo> aliasInfos) {
        this.items = items;
        Map<String, String> aliasMap = new LinkedHashMap<>();
        for (AliasInfo alias : aliasInfos) {
            aliasMap.put(alias.alias().toLowerCase(Locale.ROOT), alias.canonicalTerm().toLowerCase(Locale.ROOT));
        }
        this.aliases = aliasMap;
    }

    public MatchResult match(String description, BigDecimal threshold, BigDecimal tokenWeight, BigDecimal fuzzyWeight) {
        if (description == null || description.isBlank()) {
            return new MatchResult("NEW", BigDecimal.ZERO, "no-description", List.of());
        }
        String normalized = normalize(description);
        List<ScoredCandidate> candidates = new ArrayList<>();
        for (ItemInfo item : items) {
            String itemText = expandAliases(normalize(item.description()));
            BigDecimal tokenScore = jaccard(tokens(normalized), tokens(itemText));
            BigDecimal fuzzyScore = difflibRatio(normalized, itemText);
            BigDecimal score = tokenWeight.multiply(tokenScore)
                    .add(fuzzyWeight.multiply(fuzzyScore))
                    .setScale(6, RoundingMode.HALF_UP);
            candidates.add(new ScoredCandidate(item.itemCode(), score));
        }
        candidates.sort(Comparator.comparing(ScoredCandidate::score).reversed()
                .thenComparing(ScoredCandidate::itemCode));
        ScoredCandidate best = candidates.isEmpty() ? new ScoredCandidate("NEW", BigDecimal.ZERO) : candidates.get(0);
        List<String> top = candidates.stream().limit(5).map(ScoredCandidate::itemCode).toList();
        if (best.score().compareTo(threshold) < 0) {
            return new MatchResult("NEW", best.score(), "below-threshold", top);
        }
        return new MatchResult(best.itemCode(), best.score(), "description", top);
    }

    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT)
                .replace('"', ' ')
                .replace('\'', ' ')
                .replace('’', ' ')
                .replace('“', ' ')
                .replace('”', ' ');
        lower = lower.replaceAll("[-/(),.;:]+", " ");
        lower = lower.replaceAll("\\s+", " ").trim();
        return expandAliases(lower);
    }

    private String expandAliases(String text) {
        if (aliases.isEmpty()) {
            return text;
        }
        String replaced = text;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (replaced.contains(alias.getKey())) {
                replaced = replaced.replaceAll("(?<![a-z0-9])" + java.util.regex.Pattern.quote(alias.getKey()) + "(?![a-z0-9])",
                        alias.getValue());
            }
        }
        return replaced;
    }

    private Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.split("\\s+"))
                .map(token -> token.replaceAll("[^a-z0-9]", ""))
                .filter(token -> !token.isBlank())
                .filter(token -> !NOISE.contains(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BigDecimal jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return BigDecimal.ONE;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        if (union.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(intersection.size())
                .divide(new BigDecimal(union.size()), 6, RoundingMode.HALF_UP);
    }

    public BigDecimal difflibRatio(String left, String right) {
        if (left == null || right == null) {
            return BigDecimal.ZERO;
        }
        int total = left.length() + right.length();
        if (total == 0) {
            return BigDecimal.ONE;
        }
        int matches = matchingCharacters(left, right, 0, left.length(), 0, right.length());
        return new BigDecimal(2L * matches).divide(new BigDecimal(total), 6, RoundingMode.HALF_UP);
    }

    private int matchingCharacters(String a, String b, int alo, int ahi, int blo, int bhi) {
        int[] block = longestMatch(a, b, alo, ahi, blo, bhi);
        int ai = block[0];
        int bj = block[1];
        int size = block[2];
        if (size == 0) {
            return 0;
        }
        return size
                + matchingCharacters(a, b, alo, ai, blo, bj)
                + matchingCharacters(a, b, ai + size, ahi, bj + size, bhi);
    }

    private int[] longestMatch(String a, String b, int alo, int ahi, int blo, int bhi) {
        int bestI = alo;
        int bestJ = blo;
        int bestSize = 0;
        Map<Integer, Integer> previous = new LinkedHashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> current = new LinkedHashMap<>();
            for (int j = blo; j < bhi; j++) {
                if (a.charAt(i) == b.charAt(j)) {
                    int size = previous.getOrDefault(j - 1, 0) + 1;
                    current.put(j, size);
                    if (size > bestSize) {
                        bestSize = size;
                        bestI = i - size + 1;
                        bestJ = j - size + 1;
                    }
                }
            }
            previous = current;
        }
        return new int[]{bestI, bestJ, bestSize};
    }

    public record ScoredCandidate(String itemCode, BigDecimal score) {
    }

    public record MatchResult(String itemCode, BigDecimal score, String method, List<String> candidates) {
    }
}
