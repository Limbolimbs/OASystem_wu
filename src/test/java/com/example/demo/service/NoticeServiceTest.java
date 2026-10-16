package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.example.demo.entity.*;
import com.example.demo.notice.*;
import com.example.demo.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class NoticeServiceTest {
    NoticeRepository notices;
    NoticeReadRepository reads;
    NoticeService service;
    User actor;
    Notice row;
    @BeforeEach void setup() {
        notices = mock(NoticeRepository.class); reads = mock(NoticeReadRepository.class); service = new NoticeService(notices, reads);
        actor = new User(); actor.setId(2L); actor.setRole("ADMIN"); actor.setRealName("管理者");
        row = new Notice(); row.setTitle("案内"); row.setContent("本文"); row.setCreatedBy(actor); row.setUpdatedBy(actor);
        ReflectionTestUtils.setField(row, "id", 1L); ReflectionTestUtils.setField(row, "version", 2L);
        when(notices.detail(1L)).thenReturn(Optional.of(row));
    }
    NoticeForm form(Long id, Long version, String title, NoticeStatus status) { return new NoticeForm(id,version,title,"本文","一般",NoticePriority.NORMAL,status); }
    @Test void onlyAdminAndGeneralAffairsCanWrite() {
        actor.setRole("EMPLOYEE"); assertFalse(service.canEdit(actor));
        assertThrows(ResponseStatusException.class, () -> service.save(actor,form(null,null,"件名",NoticeStatus.PUBLISHED)));
        actor.setRole("GENERAL_AFFAIRS"); assertTrue(service.canEdit(actor));
        service.save(actor,form(null,null," 件名 ",NoticeStatus.DRAFT));
        verify(notices).saveAndFlush(argThat(n -> n.getTitle().equals("件名") && n.getCreatedBy() == actor && n.getPublishedAt() == null));
        actor.setStatus(0); assertFalse(service.canEdit(actor));
    }
    @Test void employeeCannotReadDraftOrInactiveEvenWithKnownId() {
        actor.setRole("EMPLOYEE");
        assertThrows(ResponseStatusException.class, () -> service.detail(actor,1L));
        row.setStatus(NoticeStatus.PUBLISHED); assertEquals("案内", service.detail(actor,1L).title());
        row.softDelete(); assertThrows(ResponseStatusException.class, () -> service.detail(actor,1L));
    }
    @Test void rejectsEmployeeManagementFilterAndBadInputs() {
        actor.setRole("EMPLOYEE"); assertThrows(ResponseStatusException.class, () -> service.list(actor,"","DRAFT",0));
        actor.setRole("ADMIN"); assertThrows(IllegalArgumentException.class, () -> service.list(actor,"a".repeat(101),"ALL",0));
        assertThrows(IllegalArgumentException.class, () -> service.save(actor,form(null,null," ",NoticeStatus.DRAFT)));
        assertThrows(IllegalArgumentException.class, () -> service.save(actor,form(null,null,"x".repeat(121),NoticeStatus.DRAFT)));
    }
    @Test void checksVersionBeforeUpdateOrDeactivate() {
        assertThrows(ResponseStatusException.class, () -> service.save(actor,form(1L,1L,"修正",NoticeStatus.PUBLISHED)));
        assertThrows(ResponseStatusException.class, () -> service.deactivate(actor,1L,null));
        verify(notices,never()).flush(); verify(notices,never()).saveAndFlush(any());
    }
    @Test void logicalDeleteAndRestoreKeepRecordAndRequireRepublish() {
        row.setStatus(NoticeStatus.PUBLISHED); service.deactivate(actor,1L,2L); assertTrue(row.isDeleted());
        service.restore(actor,1L,2L); assertFalse(row.isDeleted()); assertNull(row.getDeletedAt());
        assertEquals(NoticeStatus.DRAFT,row.getStatus()); verify(notices,never()).delete(any());
    }
    @Test void publishedChangesGetNewPublicationTime() {
        service.save(actor,form(1L,2L,"修正",NoticeStatus.PUBLISHED));
        assertNotNull(row.getPublishedAt()); assertEquals("修正",row.getTitle());
    }
    @Test void readAcknowledgementUsesActorAndDisplayedVersion() {
        row.setStatus(NoticeStatus.PUBLISHED); actor.setRole("EMPLOYEE");
        when(notices.findForUpdate(1L)).thenReturn(Optional.of(row));
        when(reads.findByNotice_IdAndUser_Id(1L,2L)).thenReturn(Optional.empty());
        service.markRead(actor,1L,2L);
        verify(reads).saveAndFlush(argThat(r -> r.getNotice() == row && r.getReadVersion().equals(2L)));
        assertThrows(ResponseStatusException.class, () -> service.markRead(actor,1L,1L));
    }
    @Test void alreadyReadPublishedVersionDoesNotInsertOrUpdateAgain() {
        row.setStatus(NoticeStatus.PUBLISHED); actor.setRole("EMPLOYEE");
        NoticeRead read = new NoticeRead(); read.markRead(row,actor);
        when(notices.findForUpdate(1L)).thenReturn(Optional.of(row));
        when(reads.findByNotice_IdAndUser_Id(1L,2L)).thenReturn(Optional.of(read));
        service.markRead(actor,1L,2L);
        var order = inOrder(notices,reads);
        order.verify(notices).findForUpdate(1L);
        order.verify(reads).findByNotice_IdAndUser_Id(1L,2L);
        verify(reads,never()).saveAndFlush(any());
    }
    @Test void readAcknowledgementRejectsDraftDeletedAndStaleVersionBeforeSaving() {
        actor.setRole("EMPLOYEE"); when(notices.findForUpdate(1L)).thenReturn(Optional.of(row));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.markRead(actor,1L,2L)).getStatusCode().value());
        row.setStatus(NoticeStatus.PUBLISHED);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.markRead(actor,1L,1L)).getStatusCode().value());
        row.softDelete();
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.markRead(actor,1L,2L)).getStatusCode().value());
        verifyNoInteractions(reads);
    }
}
