package com.example.demo.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import com.example.demo.entity.Department;
import com.example.demo.entity.SalesData;
import com.example.demo.entity.User;
import com.example.demo.repository.SalesDataRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AccountSecurityState;
import com.example.demo.sales.SalesForm;
import com.example.demo.security.AccessControlService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class SalesService {
    private final SalesDataRepository sales;
    private final DepartmentService departments;
    private final AccessControlService access;
    private final UserRepository users;
    private final AccountSecurityState securityState;

    public SalesService(SalesDataRepository sales, DepartmentService departments, AccessControlService access,
                        UserRepository users, AccountSecurityState securityState) {
        this.sales = sales;
        this.departments = departments;
        this.access = access;
        this.users = users;
        this.securityState = securityState;
    }

    public List<SalesData> findVisible(User actor) {
        requireSalesAccess(actor);
        return access.isAdmin(actor) ? sales.findAllByOrderBySalesMonthDescIdDesc()
                : sales.findByDepartmentOrderBySalesMonthDescIdDesc(actor.getDepartment());
    }

    public SalesData findEditable(User actor, Long id) {
        requireSalesAccess(actor);
        SalesData row = sales.findById(id)
                .filter(value -> !value.isDeleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "営業実績が見つかりません。"));
        if (!access.isAdmin(actor) && !Objects.equals(row.getDepartment(), actor.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "他部署の営業実績は操作できません。");
        }
        return row;
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "SALES_SAVE", target = "SALES")
    public SalesData save(User actor, SalesForm form) {
        // 所属・権限・部署設定の管理変更と保存を直列化し、要求開始時の古い本人情報を使わない。
        securityState.lockManagement();
        actor = users.findForUpdate(actor.getId()).filter(User::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "営業実績を操作する権限がありません。"));
        requireSalesAccess(actor);
        validate(form);
        String name = form.department() == null ? "" : form.department().trim();
        if (!access.isAdmin(actor) && !Objects.equals(name, actor.getDepartment())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "所属部署の営業実績のみ登録できます。");
        }
        Department department = access.isAdmin(actor) && form.id() != null
                ? departments.requireRegistered(name) : departments.requireActive(name);
        if (!access.isAdmin(actor) && !department.isSalesEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "営業実績の登録権限がありません。");
        }
        SalesData row;
        if (form.id() == null) {
            if (sales.existsBySalesMonthAndDepartment(form.salesMonth(), name)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "対象月・部署の実績は登録済みです。一覧の「編集」から変更してください。");
            }
            row = new SalesData();
            row.setSalesMonth(form.salesMonth());
            row.setDepartment(department.getName());
        } else {
            row = findEditable(actor, form.id());
            if (form.version() == null || !Objects.equals(form.version(), row.getVersion())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "他の利用者が実績を更新しています。最新情報を再取得してください。");
            }
            if (!Objects.equals(row.getSalesMonth(), form.salesMonth()) || !Objects.equals(row.getDepartment(), name)) {
                throw new IllegalArgumentException("編集中の対象月・部署は変更できません。");
            }
        }
        row.setSalesAmount(form.salesAmount());
        row.setOrderCount(form.orderCount());
        row.setCustomerCount(form.customerCount());
        row.setNotes(form.notes() == null ? "" : form.notes().trim());
        row.setUpdatedBy(actor.getId());
        sales.saveAndFlush(row);
        return row;
    }

    private void requireSalesAccess(User actor) {
        if (!actor.isActive() || !access.canManageSales(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "営業実績を操作する権限がありません。");
        }
    }

    private void validate(SalesForm form) {
        try {
            if (form.salesMonth() == null || !form.salesMonth().matches("[0-9]{4}-[0-9]{2}")) {
                throw new DateTimeParseException("", "", 0);
            }
            YearMonth month = YearMonth.parse(form.salesMonth());
            if (month.getYear() < 2000 || month.getYear() > 9999) throw new DateTimeParseException("", "", 0);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("対象月は2000年以降の年月を指定してください。");
        }
        BigDecimal amount = form.salesAmount();
        if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("9999999999999999.99")) > 0
                || form.orderCount() == null || form.orderCount() < 0
                || form.customerCount() == null || form.customerCount() < 0) {
            throw new IllegalArgumentException("売上高は0以上・小数2桁以内、件数と人数は0以上の整数で入力してください。");
        }
        if (form.notes() != null && form.notes().length() > 500) throw new IllegalArgumentException("確認メモは500文字以内で入力してください。");
        if (!Boolean.TRUE.equals(form.confirmed())) throw new IllegalArgumentException("営業実績の内容を確認し、確認済みにチェックしてください。");
    }
}
