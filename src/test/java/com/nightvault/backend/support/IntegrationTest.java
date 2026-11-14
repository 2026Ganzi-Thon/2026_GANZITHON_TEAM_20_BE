package com.nightvault.backend.support;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 통합 테스트 공통 부모.
 *
 * <ul>
 *   <li>컨테이너는 JVM당 하나만 띄워 모든 테스트 클래스가 공유한다(싱글턴 컨테이너). 종료는 Testcontainers(Ryuk)가 맡는다.</li>
 *   <li>스프링 컨텍스트도 설정이 같으면 캐시되므로, 하위 클래스는 컨텍스트 설정(@MockitoBean, 추가 properties 등)을 되도록 늘리지 않는다.</li>
 *   <li>각 테스트 시작 전에 Flyway 이력을 뺀 모든 테이블을 비운다. 테스트 데이터는 {@link WorryFixtures}로 넣는다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(WorryFixtures.class)
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected WorryFixtures fixtures;

    @BeforeEach
    void cleanDatabase() {
        List<String> tables = jdbc.sql("""
                SELECT quote_ident(tablename)
                FROM pg_tables
                WHERE schemaname = current_schema()
                  AND tablename <> 'flyway_schema_history'
                """)
            .query(String.class).list();
        if (!tables.isEmpty()) {
            jdbc.sql("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE").update();
        }
    }
}
