package com.example.demo.notice;

/** フォームから受け取れる項目だけを定義する。作成者・状態フラグは受け取らない。 */
public record NoticeForm(Long id, Long version, String title, String content,
                         String category, NoticePriority priority, NoticeStatus status) { }
