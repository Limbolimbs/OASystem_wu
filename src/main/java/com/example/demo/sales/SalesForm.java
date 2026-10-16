package com.example.demo.sales;

import java.math.BigDecimal;

/** 更新者・日時はサーバー側で設定する。 */
public record SalesForm(Long id, Long version, String salesMonth, String department,
        BigDecimal salesAmount, Integer orderCount, Integer customerCount, String notes, Boolean confirmed) { }
