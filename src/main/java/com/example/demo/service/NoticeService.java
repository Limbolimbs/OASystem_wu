package com.example.demo.service;

import java.time.LocalDateTime;
import java.util.*;
import com.example.demo.attendance.AttendanceClock;
import com.example.demo.entity.*;
import com.example.demo.notice.*;
import com.example.demo.repository.*;
import com.example.demo.security.SystemRole;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class NoticeService {
    private final NoticeRepository notices;
    private final NoticeReadRepository reads;
    public NoticeService(NoticeRepository notices, NoticeReadRepository reads) { this.notices = notices; this.reads = reads; }

    public record Summary(Long id, Long version, String title, String category, NoticePriority priority,
        NoticeStatus status, String author, LocalDateTime publishedAt, LocalDateTime updatedAt, boolean read, boolean deleted) { }
    public record Detail(Long id, Long version, String title, String content, String category, NoticePriority priority,
        NoticeStatus status, String author, LocalDateTime publishedAt, LocalDateTime updatedAt, boolean deleted) { }

    public boolean canEdit(User actor) {
        SystemRole role = SystemRole.fromCode(actor.getRole());
        return actor.isActive() && (role == SystemRole.ADMIN || role == SystemRole.GENERAL_AFFAIRS);
    }

    public Page<Summary> list(User actor, String keyword, String filter, int page) {
        boolean manager = canEdit(actor);
        String word = keyword == null ? "" : keyword.trim();
        if (word.length() > 100) throw new IllegalArgumentException("検索語は100文字以内で入力してください。");
        if (!Set.of("ALL", "PUBLISHED", "DRAFT", "INACTIVE").contains(filter)) throw new IllegalArgumentException("表示対象を確認してください。");
        if (!manager && !Set.of("ALL", "PUBLISHED").contains(filter)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        NoticeStatus status = switch (filter) { case "DRAFT" -> NoticeStatus.DRAFT; case "PUBLISHED" -> NoticeStatus.PUBLISHED; default -> null; };
        String pattern = "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        int selected = Math.max(0, Math.min(page, 1000));
        Page<Notice> result = notices.search(manager, "INACTIVE".equals(filter) ? 1 : 0, status, pattern, PageRequest.of(selected, 20));
        int last = Math.max(0, result.getTotalPages() - 1);
        if (selected > last) result = notices.search(manager, "INACTIVE".equals(filter) ? 1 : 0, status, pattern, PageRequest.of(last, 20));
        var ids = result.getContent().stream().map(Notice::getId).toList();
        Map<Long, Long> readVersions = new HashMap<>();
        if (!ids.isEmpty()) reads.findReadVersions(actor.getId(), ids).forEach(r -> readVersions.put(r.getNotice().getId(), r.getReadVersion()));
        return result.map(n -> new Summary(n.getId(), n.getVersion(), n.getTitle(), n.getCategory(), n.getPriority(), n.getStatus(),
            n.getCreatedBy().getRealName(), n.getPublishedAt(), n.getUpdatedAt(), Objects.equals(n.getVersion(), readVersions.get(n.getId())), n.isDeleted()));
    }

    public long unreadCount(User actor) { return notices.unreadCount(actor.getId()); }

    public Detail detail(User actor, Long id) {
        Notice n = visible(actor, id);
        return new Detail(n.getId(), n.getVersion(), n.getTitle(), n.getContent(), n.getCategory(), n.getPriority(), n.getStatus(),
            n.getCreatedBy().getRealName(), n.getPublishedAt(), n.getUpdatedAt(), n.isDeleted());
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "NOTICE_SAVE", target = "NOTICE")
    public Notice save(User actor, NoticeForm form) {
        requireEditor(actor);
        String title = form.title() == null ? "" : form.title().trim();
        String content = form.content() == null ? "" : form.content().trim();
        if (title.isBlank() || title.length() > 120 || content.isBlank() || content.length() > 10000
                || !Set.of("一般", "人事・総務", "勤怠", "営業", "システム").contains(form.category() == null ? "" : form.category())
                || form.priority() == null || form.status() == null) {
            throw new IllegalArgumentException("件名（120文字以内）・本文（10000文字以内）・分類・重要度・公開状態を確認してください。");
        }
        Notice n;
        if (form.id() == null) {
            n = new Notice(); n.setCreatedBy(actor);
        } else {
            n = visible(actor, form.id()); requireVersion(n, form.version());
            if (n.isDeleted()) throw new IllegalStateException("無効なお知らせは先に復元してください。");
        }
        n.setTitle(title); n.setContent(content); n.setCategory(form.category()); n.setPriority(form.priority()); n.setStatus(form.status());
        n.setUpdatedBy(actor);
        // 公開版を変更すると未読へ戻る。既読記録は削除せず版数で判定する。
        n.setPublishedAt(form.status() == NoticeStatus.PUBLISHED ? AttendanceClock.now() : null);
        notices.saveAndFlush(n);
        return n;
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "NOTICE_DEACTIVATE", target = "NOTICE", idArgument = 1)
    public void deactivate(User actor, Long id, Long version) {
        requireEditor(actor); Notice n = visible(actor, id); requireVersion(n, version);
        if (n.isDeleted()) throw new IllegalStateException("このお知らせはすでに無効です。");
        n.softDelete(); n.setUpdatedBy(actor); notices.flush();
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "NOTICE_RESTORE", target = "NOTICE", idArgument = 1)
    public void restore(User actor, Long id, Long version) {
        requireEditor(actor); Notice n = visible(actor, id); requireVersion(n, version);
        if (!n.isDeleted()) throw new IllegalStateException("このお知らせはすでに有効です。");
        n.restore(); n.setStatus(NoticeStatus.DRAFT); n.setPublishedAt(null); n.setUpdatedBy(actor); notices.flush();
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "NOTICE_READ", target = "NOTICE", idArgument = 1)
    public void markRead(User actor, Long id, Long version) {
        // 同一公開版の重複既読を直列化し、版数確認から既読保存まで更新と競合させない。
        Notice n = visible(actor, notices.findForUpdate(id)); requireVersion(n, version);
        if (n.isDeleted() || n.getStatus() != NoticeStatus.PUBLISHED) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        NoticeRead r = reads.findByNotice_IdAndUser_Id(id, actor.getId()).orElseGet(NoticeRead::new);
        if (!r.isDeleted() && Objects.equals(r.getReadVersion(), n.getVersion())) return;
        r.markRead(n, actor); reads.saveAndFlush(r);
    }

    private Notice visible(User actor, Long id) {
        return visible(actor, notices.detail(id));
    }
    private Notice visible(User actor, Optional<Notice> result) {
        Notice n = result.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!actor.isActive() || (!canEdit(actor) && (n.isDeleted() || n.getStatus() != NoticeStatus.PUBLISHED)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return n;
    }
    private void requireEditor(User actor) { if (!canEdit(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private void requireVersion(Notice n, Long version) {
        if (version == null || !version.equals(n.getVersion())) throw new ResponseStatusException(HttpStatus.CONFLICT, "お知らせが更新されています。一覧から最新内容を確認してください。");
    }
}
