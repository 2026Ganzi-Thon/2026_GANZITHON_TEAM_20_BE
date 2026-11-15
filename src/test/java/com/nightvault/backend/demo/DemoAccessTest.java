package com.nightvault.backend.demo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.junit.jupiter.api.Test;

/** 설정에 따른 시연 키 판정(DEMO-01). 통합 테스트 프로필은 시연을 켜 두므로 꺼짐 동작은 여기서 확인한다. */
class DemoAccessTest {

    @Test
    void 시연이_꺼져_있으면_키가_맞아도_404다() {
        var access = new DemoAccess(null, false, "secret-key");
        assertThatThrownBy(() -> access.requireKey("secret-key"))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertNotFound(e));
    }

    @Test
    void 시연_키가_설정되지_않았으면_켜져_있어도_404다() {
        var access = new DemoAccess(null, true, " ");
        assertThatThrownBy(() -> access.requireKey(" "))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertNotFound(e));
        assertThatThrownBy(() -> access.requireKey(null))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertNotFound(e));
    }

    @Test
    void 켜져_있고_키가_같을_때만_통과한다() {
        var access = new DemoAccess(null, true, "secret-key");
        assertThatCode(() -> access.requireKey("secret-key")).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireKey("secret-key2")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> access.requireKey(null)).isInstanceOf(BusinessException.class);
    }

    private static void assertNotFound(BusinessException exception) {
        if (exception.error() != ApiError.NOT_FOUND) {
            throw new AssertionError("expected NOT_FOUND but was " + exception.error());
        }
    }
}
