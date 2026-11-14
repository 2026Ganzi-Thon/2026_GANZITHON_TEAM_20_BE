package com.nightvault.backend.analysis;

import java.util.List;

import com.nightvault.backend.worry.HelpType;

public record AnalysisContent(
    String summary, Recommendation recommendation, List<String> actionSteps, String restGuide, String empathyMessage
) {
    public AnalysisContent {
        if (recommendation == null || actionSteps == null || actionSteps.size() != 3) {
            throw new IllegalArgumentException("Invalid analysis content");
        }
        requireText(summary, 500);
        requireText(restGuide, 500);
        requireText(empathyMessage, 500);
        actionSteps.forEach(step -> requireText(step, 200));
        actionSteps = List.copyOf(actionSteps);
    }

    public record Recommendation(HelpType type, String reason) {
        public Recommendation {
            if (type == null) {
                throw new IllegalArgumentException("Invalid recommendation");
            }
            requireText(reason, 300);
        }
    }

    private static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException("Invalid analysis content");
        }
    }
}
