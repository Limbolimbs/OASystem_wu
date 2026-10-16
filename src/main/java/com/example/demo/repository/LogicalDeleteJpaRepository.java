package com.example.demo.repository;

import java.util.List;
import com.example.demo.entity.LogicalDeleteEntity;
import com.example.demo.entity.AttendanceAuditLog;
import com.example.demo.entity.AttendanceEvent;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.DeleteSpecification;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * 全リポジトリ共通。delete は UPDATE に置き換え、一括物理削除は拒否する。
 * findById/getReferenceById は復元・履歴参照のため無効データも取得する。
 * 通常の一覧・件数は有効データのみ。業務固有の検索も deleteFlag を指定する。
 */
@Transactional(readOnly = true)
public class LogicalDeleteJpaRepository<T extends LogicalDeleteEntity, ID> extends SimpleJpaRepository<T, ID> {
    private final JpaEntityInformation<T, ?> entityInformation;

    public LogicalDeleteJpaRepository(JpaEntityInformation<T, ?> information, EntityManager entityManager) {
        super(information, entityManager);
        entityInformation = information;
    }

    private Specification<T> active() { return (root, query, builder) -> builder.equal(root.get("deleteFlag"), 0); }

    @Override public List<T> findAll() { return super.findAll(active()); }
    @Override public List<T> findAll(Sort sort) { return super.findAll(active(), sort); }
    @Override public Page<T> findAll(Pageable pageable) { return super.findAll(active(), pageable); }
    @Override public List<T> findAllById(Iterable<ID> ids) {
        return super.findAllById(ids).stream().filter(value -> !value.isDeleted()).toList();
    }
    @Override public long count() { return super.count(active()); }

    @Override @Transactional
    public void delete(T entity) {
        Assert.notNull(entity, "削除対象を指定してください。");
        if (entity instanceof AttendanceEvent || entity instanceof AttendanceAuditLog) {
            throw new UnsupportedOperationException("打刻原本・監査履歴は削除できません。");
        }
        Object identifier = entityInformation.getId(entity);
        if (identifier == null) return;
        @SuppressWarnings("unchecked") ID id = (ID) identifier;
        // IDのみ指定したオブジェクトでも他の列をNULLで上書きしないよう、保存済み行を取得する。
        findById(id).ifPresent(stored -> {
            stored.softDelete();
            save(stored);
        });
    }

    @Override @Transactional
    public void deleteById(ID id) { findById(id).ifPresent(this::delete); }
    @Override @Transactional
    public void deleteAll(Iterable<? extends T> entities) { entities.forEach(this::delete); }
    @Override @Transactional
    public void deleteAllById(Iterable<? extends ID> ids) { ids.forEach(this::deleteById); }

    private UnsupportedOperationException bulkDeleteError() {
        return new UnsupportedOperationException("一括削除は利用できません。対象を指定して論理削除してください。");
    }
    @Override @Transactional public void deleteAll() { throw bulkDeleteError(); }
    @Override @Transactional public void deleteAllInBatch() { throw bulkDeleteError(); }
    @Override @Transactional public void deleteAllInBatch(Iterable<T> entities) { throw bulkDeleteError(); }
    @Override @Transactional public void deleteAllByIdInBatch(Iterable<ID> ids) { throw bulkDeleteError(); }
    @Override @Transactional public long delete(DeleteSpecification<T> specification) { throw bulkDeleteError(); }
}
