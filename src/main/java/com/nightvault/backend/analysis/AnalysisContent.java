package com.nightvault.backend.analysis;

import java.util.List;

record AnalysisContent(
    String summary, Recommendation recommendation, List<String> actionSteps, String restGuide, String empathyMessage
) {
    AnalysisContent {
        if (recommendation == null || actionSteps == null || actionSteps.size() != 3) {
            throw new IllegalArgumentException("Invalid analysis content");
        }
        requireText(summary, 500);
        requireText(restGuide, 500);
        requireText(empathyMessage, 500);
        actionSteps.forEach(step -> requireText(step, 200));
        actionSteps = List.copyOf(actionSteps);
    }

    record Recommendation(HelpType type, String reason) {
        Recommendation {
            if (type == null) {
                throw new IllegalArgumentException("Invalid recommendation");
            }
            requireText(reason, 300);
        }
    }

    enum HelpType { SHRED, ACTION, REST, EMPATHY }

    private static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit) {
            throw new IllegalArgumentException("Invalid analysis content");
        }
    }
}
