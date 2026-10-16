package com.example.demo.repository;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import com.example.demo.entity.*;
import jakarta.persistence.EntityManager;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** DB接続なしで全エンティティ・JPQL・派生検索・共通Repositoryの生成を検証する。 */
class RepositoryMappingTest {
    @Test void allRepositoriesCanBeCreatedUsingMysqlMappingsWithoutConnectingToCloud() {
        Configuration config = new Configuration();
        List.of(User.class, Department.class, SalesData.class, AttendanceRecord.class,
                AttendancePolicy.class, AttendancePolicyHistory.class, CompanyHoliday.class,
                WorkSchedule.class, AttendanceRequest.class, AttendanceBreak.class,
                AttendanceEvent.class, AttendanceAuditLog.class).forEach(config::addAnnotatedClass);
        config.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        config.setProperty("hibernate.boot.allow_jdbc_metadata_access", "false");
        config.setProperty("hibernate.hbm2ddl.auto", "none");
        config.setProperty("hibernate.connection.provider_class", UserSuppliedConnectionProviderImpl.class.getName());
        try (var sessionFactory = config.buildSessionFactory(); EntityManager em = sessionFactory.createEntityManager()) {
            var factory = new JpaRepositoryFactory(em);
            factory.setRepositoryBaseClass(LogicalDeleteJpaRepository.class);
            List<String> queryMethods = new ArrayList<>();
            factory.addQueryCreationListener(query -> queryMethods.add(query.getQueryMethod().getName()));
            List.of(UserRepository.class, DepartmentRepository.class, SalesDataRepository.class,
                    AttendanceRecordRepository.class, AttendancePolicyRepository.class,
                    AttendancePolicyHistoryRepository.class, CompanyHolidayRepository.class,
                    WorkScheduleRepository.class, AttendanceRequestRepository.class,
                    AttendanceBreakRepository.class, AttendanceEventRepository.class,
                    AttendanceAuditLogRepository.class).forEach(type -> assertNotNull(factory.getRepository(type)));
            assertTrue(queryMethods.contains("existsById"), "会社休日の存在確認にも有効条件付きJPQLを使う");
            assertThrows(UnsupportedOperationException.class, () -> factory.getRepository(UserRepository.class).deleteAllInBatch());
        }
    }
}
