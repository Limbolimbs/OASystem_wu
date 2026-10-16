package com.example.demo.repository;

import java.util.List;
import java.util.Optional;
import com.example.demo.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    List<Department> findAllByOrderByStatusDescIdAsc();
    @Query("select d from Department d where d.status = ?1 and d.deleteFlag = 0 order by d.id")
    List<Department> findByStatusOrderByIdAsc(Integer status);
    @Query("select d from Department d where d.status = ?1 and d.salesEnabled = true and d.deleteFlag = 0 order by d.id")
    List<Department> findByStatusAndSalesEnabledTrueOrderByIdAsc(Integer status);
    @Query("select d from Department d where d.name = ?1 and d.status = ?2 and d.deleteFlag = 0")
    Optional<Department> findByNameAndStatus(String name, Integer status);
    Optional<Department> findByName(String name);
    boolean existsByCodeOrName(String code, String name);
}
