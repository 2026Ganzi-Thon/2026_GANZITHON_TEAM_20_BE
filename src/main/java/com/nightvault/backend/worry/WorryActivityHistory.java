package com.nightvault.backend.worry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WorryActivityHistory {
    Map<UUID, Latest> latestFor(UUID guestId, List<UUID> worryIds);

    /** 걱정의 활동 중 "도움 됨" 평가가 있는 활동의 유형. 여럿이면 가장 최근에 평가한 활동이다(PAST-02). */
    Optional<HelpType> latestHelpful(UUID guestId, UUID worryId);

    record Latest(HelpType type, boolean helpful) {}
}
