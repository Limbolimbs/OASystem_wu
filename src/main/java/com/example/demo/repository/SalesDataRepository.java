package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.SalesData;

public interface SalesDataRepository extends JpaRepository<SalesData,Long>{
    
	List<SalesData>findAllByOrderBySalesMonthAscDepartmentAsc();
}
