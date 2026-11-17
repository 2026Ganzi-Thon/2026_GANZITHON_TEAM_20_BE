package com.nightvault.backend.worry;

import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.stereotype.Service;

/** 다른 패키지가 걱정의 표시용 요약(수정본 우선, M-04)을 읽을 때 쓴다. 소유권·삭제·만료·공개를 검사한다. */
@Service
public class WorrySummaries {
    private final WorryAccess access;
    private final VaultService vault;

    WorrySummaries(WorryAccess access, VaultService vault) {
        this.access = access;
        this.vault = vault;
    }

    public DisplaySummary find(UUID guestId, UUID worryId) {
        var row = access.find(guestId, worryId);
        if (row.encrypted() == null) {
            throw new BusinessException(ApiError.WORRY_LOCKED);
        }
        return new DisplaySummary(vault.displaySummary(row), row.encrypted().summaryEdited());
    }

    public record DisplaySummary(String summary, boolean edited) {}
}
