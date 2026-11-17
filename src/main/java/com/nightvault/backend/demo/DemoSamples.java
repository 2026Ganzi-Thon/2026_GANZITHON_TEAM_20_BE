package com.nightvault.backend.demo;

import java.time.Duration;
import java.util.List;

import com.nightvault.backend.analysis.AnalysisContent;
import com.nightvault.backend.analysis.AnalysisContent.Recommendation;
import com.nightvault.backend.worry.HelpType;

/**
 * 시연 프리셋(DEMO-03)과 시연 게스트 제출용 예시 콘텐츠. 실제 AI 응답과 구분되도록 {@value #MARK}를 사용한다.
 * 위기 안내(SUPPORT_GUIDANCE) 예시는 두지 않아 시연에서 전화 연결 화면이 자동으로 열리지 않는다.
 */
public final class DemoSamples {
    static final String MARK = "[시연 예시] ";

    /** 분석 실패 예시는 걱정 행 없이 실패한 요청만 만든다. 홈 실패 카드는 접수 후 24시간 동안 보인다. */
    static final Duration FAILED_ACCEPTED_AGO = Duration.ofMinutes(5);

    private static final AnalysisContent SUBMISSION = content(
        "지금 마음에 걸린 걱정을 기록했어요.", HelpType.ACTION,
        "지금 할 수 있는 일을 작은 단계로 나누는 흐름을 확인할 수 있어요.",
        List.of(
            MARK + "마음에 걸리는 점 하나를 적어 봐요.",
            MARK + "지금 할 수 있는 작은 행동을 하나 골라 봐요.",
            MARK + "해 본 일을 짧게 적어 봐요."
        )
    );

    private DemoSamples() {}

    /** 시연 게스트의 새 제출과 재시도에 쓰는 고정 응답. */
    public static AnalysisContent forSubmission() {
        return SUBMISSION;
    }

    enum Kind { LOCKED, PUBLISHED, REFLECTION }

    /**
     * 걱정 한 건의 예시. 시각은 현재 기준 상대값이다.
     * {@code publishFromNow}가 음수면 이미 공개됐고, {@code firstViewedAgo}가 있으면 이미 열람한 기록이다.
     */
    record Sample(Kind kind, String text, int anxiety, Duration createdAgo, Duration publishFromNow,
                  Duration firstViewedAgo, AnalysisContent content) {}

    static final List<Sample> WORRIES = List.of(
        // 잠김: 내일 공개. "공개 시각으로 이동"으로 연다.
        new Sample(Kind.LOCKED, MARK + "내일 아침 발표에서 말이 막힐까 봐 걱정돼요.", 4,
            Duration.ofMinutes(10), Duration.ofDays(1), null,
            content("발표 중 말이 막힐까 봐 긴장하고 있어요.", HelpType.ACTION,
                "준비할 수 있는 부분을 작게 나누면 막막함이 줄어들 수 있어요.",
                List.of("첫 문장만 소리 내어 읽어 봐요.", "막힐 때 볼 핵심 단어 세 개를 적어요.",
                    "발표 시작 전 숨을 한 번 고르기로 정해요."))),
        // 공개됨·미열람: 한 시간 전에 공개.
        new Sample(Kind.PUBLISHED, MARK + "친구에게 보낸 메시지에 답이 없어서 마음이 쓰여요.", 3,
            Duration.ofDays(1), Duration.ofHours(-1), null,
            content("답이 없는 메시지 때문에 관계가 걱정되고 있어요.", HelpType.EMPATHY,
                "지금은 해결보다 마음을 알아주는 말이 먼저 필요해 보여요.",
                List.of("메시지를 보낸 이유를 한 줄로 적어요.", "기다릴 시간을 스스로 정해요.",
                    "그 사이 할 일을 하나 골라요."))),
        // 회고 대상: 이틀 전 공개·열람, 아직 회고 응답 없음(공개 후 24시간 경과).
        new Sample(Kind.REFLECTION, MARK + "이번 달 지출이 많아서 다음 달이 걱정돼요.", 4,
            Duration.ofDays(3), Duration.ofDays(-2), Duration.ofDays(2).minusMinutes(30),
            content("지출이 늘어 다음 달 생활이 걱정되고 있어요.", HelpType.REST,
                "숫자를 바로 정리하기보다 잠시 쉬고 나서 보는 편이 도움이 될 수 있어요.",
                List.of("이번 달 큰 지출 세 가지만 적어요.", "줄일 수 있는 항목 하나를 골라요.",
                    "다음 주에 다시 확인할 날을 정해요.")))
    );

    private static AnalysisContent content(String summary, HelpType type, String reason, List<String> steps) {
        return new AnalysisContent(MARK + summary, new Recommendation(type, MARK + reason), steps,
            MARK + "3분 동안 어깨 힘을 빼고 천천히 숨을 쉬어 봐요.",
            MARK + "그런 마음이 드는 건 자연스러운 일이에요. 여기까지 적어 둔 것만으로도 충분해요.");
    }
}
