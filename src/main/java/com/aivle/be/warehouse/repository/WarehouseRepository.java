package com.aivle.be.warehouse.repository;

import com.aivle.be.warehouse.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    /**
     * 이 사용자가 볼 수 있는 창고.
     *
     * 공용 창고(기본 3개)와 본인이 만든 창고를 함께 돌려준다.
     */
    @Query("""
            SELECT w FROM Warehouse w
            WHERE w.shared = true
               OR w.user.id = :userId
            ORDER BY w.id
            """)
    List<Warehouse> findVisibleTo(@Param("userId") Long userId);

    /** 로그인 정보가 없을 때는 공용 창고만 보여준다. */
    @Query("SELECT w FROM Warehouse w WHERE w.shared = true ORDER BY w.id")
    List<Warehouse> findShared();
}
