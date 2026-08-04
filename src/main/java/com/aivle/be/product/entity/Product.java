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

    // 외부 API와 화면에서 사용하는 고유 업무 코드 (예: "ITEM-001")
    @Column(name = "product_code", nullable = false, unique = true, length = 20)
    private String productCode;

    // 프론트 product_name ("식품")
    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "unit", length = 20)
    private String unit;

    /** One physical transport box contains this many sellable units. */
    @Column(name = "units_per_box", nullable = false, columnDefinition = "integer default 1")
    private Integer unitsPerBox;

    @Column(name = "barcode", length = 32)
    private String barcode;

    @Column(name = "temperature_zone", length = 20)
    private String temperatureZone;

    @Column(name = "fragile")
    private Boolean fragile;

    public static Product create(
            String productCode,
            String productName,
            String category,
            String unit,
            Integer unitsPerBox,
            String barcode,
            String temperatureZone,
            Boolean fragile
    ) {
        Product product = new Product();
        product.productCode = productCode;
        product.productName = productName;
        product.category = category;
        product.unit = unit;
        product.unitsPerBox = unitsPerBox;
        product.barcode = barcode;
        product.temperatureZone = temperatureZone;
        product.fragile = fragile;
        return product;
    }

    public void update(
            String productCode,
            String productName,
            String category,
            String unit,
            Integer unitsPerBox,
            String barcode,
            String temperatureZone,
            Boolean fragile
    ) {
        this.productCode = productCode;
        this.productName = productName;
        this.category = category;
        this.unit = unit;
        this.unitsPerBox = unitsPerBox;
        this.barcode = barcode;
        this.temperatureZone = temperatureZone;
        this.fragile = fragile;
    }
}
