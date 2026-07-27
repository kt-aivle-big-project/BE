package com.aivle.be.product.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/**
 * 품목 마스터.
 * 프론트 입고 품목 구성(A=식품, B=음료 …)에 대응하며,
 * WarehouseItem.itemId 가 이 엔티티의 id 를 가리킨다.
 */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    // 프론트 product_code ("A")
    @Column(name = "product_code", nullable = false, unique = true, length = 20)
    private String productCode;

    // 프론트 product_name ("식품")
    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    public static Product create(String productCode, String productName) {
        Product product = new Product();
        product.productCode = productCode;
        product.productName = productName;
        return product;
    }

    public void update(String productCode, String productName) {
        this.productCode = productCode;
        this.productName = productName;
    }
}
