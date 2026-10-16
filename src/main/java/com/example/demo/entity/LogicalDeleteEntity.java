package com.example.demo.entity;

import java.time.LocalDateTime;
import com.example.demo.attendance.AttendanceClock;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PreRemove;

/** 全業務テーブル共通の論理削除情報。申請状態・業務状態とは別に管理する。 */
@MappedSuperclass
public abstract class LogicalDeleteEntity {
    @Column(name = "delete_flag", nullable = false)
    private int deleteFlag = 0;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    public int getDeleteFlag() { return deleteFlag; }
    public boolean isDeleted() { return deleteFlag == 1; }
    public LocalDateTime getDeletedAt() { return deletedAt; }

    /** 再度無効化しても最初の無効化日時は変更しない。 */
    public void softDelete() {
        deleteFlag = 1;
        if (deletedAt == null) deletedAt = AttendanceClock.now();
    }

    public void restore() {
        deleteFlag = 0;
        deletedAt = null;
    }

    /** 既存の在籍期間設定との互換用。削除状態は softDelete/restore で変更する。 */
    public void setDeletedAt(LocalDateTime value) { deletedAt = value; }

    @PreRemove
    void rejectPhysicalDelete() {
        throw new IllegalStateException("物理削除は利用できません。論理削除してください。");
    }
}
