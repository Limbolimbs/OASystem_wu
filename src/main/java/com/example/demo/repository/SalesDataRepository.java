package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.SalesData;

public interface SalesDataRepository extends JpaRepository<SalesData, Long> {
    @Query("select s from SalesData s where s.department = ?1 and s.deleteFlag = 0 order by s.salesMonth desc, s.id desc")
    List<SalesData> findByDepartmentOrderBySalesMonthDescIdDesc(String department);
    @Query("select s from SalesData s where s.deleteFlag = 0 order by s.salesMonth desc, s.id desc")
    List<SalesData> findAllByOrderBySalesMonthDescIdDesc();
    boolean existsBySalesMonthAndDepartment(String salesMonth, String department);
    
    @Query("select s from SalesData s where s.deleteFlag = 0 order by s.salesMonth, s.department")
    List<SalesData> findAllByOrderBySalesMonthAscDepartmentAsc();
}
