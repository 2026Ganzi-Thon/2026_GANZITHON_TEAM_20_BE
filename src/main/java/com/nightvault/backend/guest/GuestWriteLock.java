package com.nightvault.backend.guest;

import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class GuestWriteLock {
    private final JdbcClient jdbc;

    GuestWriteLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActive(UUID guestId) {
        return acquire(guestId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActive(UUID guestId, ApiError error) {
        if (!acquire(guestId)) {
            throw new BusinessException(error);
        }
    }

    private boolean acquire(UUID guestId) {
        return jdbc.sql("SELECT id FROM guests WHERE id = :id AND state = 'ACTIVE' FOR UPDATE")
            .param("id", guestId).query(UUID.class).optional().isPresent();
    }
}
