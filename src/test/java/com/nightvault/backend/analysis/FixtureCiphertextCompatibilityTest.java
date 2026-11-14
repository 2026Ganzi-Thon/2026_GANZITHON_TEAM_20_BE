package com.nightvault.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.nightvault.backend.support.IntegrationTest;
import com.nightvault.backend.support.WorryFixtures;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 픽스처가 만든 암호문을 BE1 {@link AnalysisPayloads}가 그대로 복호화할 수 있는지 확인한다. */
class FixtureCiphertextCompatibilityTest extends IntegrationTest {

    @Autowired
    AnalysisPayloads payloads;

    @Test
    void 픽스처_입력_암호문을_BE1_모듈로_복호화한다() {
        Instant created = WorryFixtures.now().minus(Duration.ofDays(2));
        WorrySpec spec = WorrySpec.of(created, created.plus(Duration.ofDays(1))).withInput("시험이 걱정돼요.", 4, "메모");
        TestWorry worry = fixtures.worry(fixtures.guest(), spec);

        byte[] ciphertext = jdbc.sql("SELECT input_ciphertext FROM worries WHERE id = :id")
            .param("id", worry.id()).query(byte[].class).single();

        assertThat(payloads.decryptInput(worry.requestId(), ciphertext))
            .isEqualTo(new WorryInput("시험이 걱정돼요.", 4, "메모", spec.publishAt()));
    }
}
