package com.example.demo.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import com.example.demo.entity.User;
import com.example.demo.entity.AttendanceAuditLog;
import com.example.demo.entity.AttendanceEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.DeleteSpecification;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;

class LogicalDeleteJpaRepositoryTest {
    @SuppressWarnings("unchecked")
    @Test void deleteAndDeleteByIdUseSaveNotEntityManagerRemove() {
        EntityManager em = entityManager();
        JpaEntityInformation<User, Long> info = mock(JpaEntityInformation.class);
        when(info.getJavaType()).thenReturn(User.class);
        LogicalDeleteJpaRepository<User, Long> repository = spy(new LogicalDeleteJpaRepository<>(info, em));
        User user = new User(); user.setId(5L);
        when(info.getId(user)).thenReturn(5L);
        doReturn(Optional.of(user)).when(repository).findById(5L);
        doReturn(user).when(repository).save(user);
        repository.delete(user);
        assertTrue(user.isDeleted()); assertEquals(0, user.getStatus());
        verify(repository).save(user); verify(em, never()).remove(any());
        user.restore();
        repository.deleteById(5L);
        assertTrue(user.isDeleted()); verify(repository, times(2)).save(user);
        verify(em, never()).remove(any());
    }

    @SuppressWarnings("unchecked")
    @Test void deletingIdOnlyObjectDoesNotOverwriteStoredColumns() {
        EntityManager em = entityManager();
        JpaEntityInformation<User, Long> info = mock(JpaEntityInformation.class);
        when(info.getJavaType()).thenReturn(User.class);
        var repository = spy(new LogicalDeleteJpaRepository<User, Long>(info, em));
        User partial = new User(); partial.setId(5L);
        User stored = new User(); stored.setId(5L); stored.setUsername("ito"); stored.setRealName("伊藤道信");
        when(info.getId(partial)).thenReturn(5L);
        doReturn(Optional.of(stored)).when(repository).findById(5L);
        doReturn(stored).when(repository).save(stored);
        repository.delete(partial);
        assertTrue(stored.isDeleted()); assertEquals("ito", stored.getUsername()); assertEquals("伊藤道信", stored.getRealName());
        verify(repository).save(stored); verify(repository, never()).save(partial);
        verify(em, never()).remove(any());
    }

    @SuppressWarnings("unchecked")
    @Test void bulkPhysicalDeletionIsBlockedBeforeAccessingDatabase() {
        EntityManager em = entityManager();
        JpaEntityInformation<User, Long> info = mock(JpaEntityInformation.class);
        when(info.getJavaType()).thenReturn(User.class);
        LogicalDeleteJpaRepository<User, Long> repository = new LogicalDeleteJpaRepository<>(info, em);
        assertThrows(UnsupportedOperationException.class, repository::deleteAll);
        assertThrows(UnsupportedOperationException.class, repository::deleteAllInBatch);
        assertThrows(UnsupportedOperationException.class, () -> repository.deleteAllInBatch(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> repository.deleteAllByIdInBatch(List.of(1L)));
        assertThrows(UnsupportedOperationException.class, () -> repository.delete((DeleteSpecification<User>) null));
        verify(em, never()).createQuery(anyString()); verify(em, never()).remove(any());
    }

    @SuppressWarnings("unchecked")
    @Test void originalPunchEventsAndAuditEvidenceCannotBeDeleted() {
        JpaEntityInformation<AttendanceEvent, Long> eventInfo = mock(JpaEntityInformation.class);
        when(eventInfo.getJavaType()).thenReturn(AttendanceEvent.class);
        var events = new LogicalDeleteJpaRepository<>(eventInfo, entityManager());
        assertThrows(UnsupportedOperationException.class, () -> events.delete(new AttendanceEvent()));
        JpaEntityInformation<AttendanceAuditLog, Long> auditInfo = mock(JpaEntityInformation.class);
        when(auditInfo.getJavaType()).thenReturn(AttendanceAuditLog.class);
        var audits = new LogicalDeleteJpaRepository<>(auditInfo, entityManager());
        assertThrows(UnsupportedOperationException.class, () -> audits.delete(new AttendanceAuditLog()));
    }

    private EntityManager entityManager() {
        EntityManager em = mock(EntityManager.class);
        when(em.getEntityManagerFactory()).thenReturn(mock(EntityManagerFactory.class));
        return em;
    }
}
