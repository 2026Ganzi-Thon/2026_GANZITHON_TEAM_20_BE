package com.nightvault.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

class IntegrationSetupTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Test
    void 테스트_프로필로_컨텍스트가_뜬다() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("NIGHTVAULT_DATA_KEY"))
            .isEqualTo("dGVzdC1vbmx5LW5pZ2h0dmF1bHQtZGF0YS1rZXktMzI=");
    }

    @Test
    void 로컬_분석_워커와_스케줄러가_꺼져_있다() {
        assertThat(context.containsBean("localAnalysisWorker")).isFalse();
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
            .isFalse();
    }

    @Test
    void 컨테이너는_PostgreSQL_17이다() {
        String version = jdbc.sql("SHOW server_version").query(String.class).single();
        assertThat(version).startsWith("17.");
    }

    @Test
    void Flyway_V1_V2가_적용된다() {
        List<String> versions = jdbc.sql("""
                SELECT version FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank
                """)
            .query(String.class).list();
        assertThat(versions).startsWith("1", "2");

        List<String> tables = jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = current_schema()")
            .query(String.class).list();
        assertThat(tables).contains(
            "guests", "guest_sessions", "guest_settings",
            "analysis_requests", "analysis_retries", "worries", "ai_contents");
    }

    @Test
    void 각_테스트는_빈_DB에서_시작한다() {
        // 다른 테스트가 넣은 행이 남아 있지 않아야 한다(실행 순서와 무관).
        for (String table : List.of("guests", "guest_sessions", "analysis_requests", "worries", "ai_contents")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single())
                .as(table).isZero();
        }
        fixtures.publicWorry(fixtures.guest());
    }
}
