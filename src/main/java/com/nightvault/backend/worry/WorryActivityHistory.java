package com.nightvault.backend.worry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WorryActivityHistory {
    Map<UUID, Latest> latestFor(UUID guestId, List<UUID> worryIds);

    record Latest(HelpType type, boolean helpful) {}
}
