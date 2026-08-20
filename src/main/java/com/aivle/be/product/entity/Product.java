package com.aivle.be.product.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long id;

    @Column(name = "product_code", nullable = false, unique = true, length = 20)
    private String productCode;

    // 프론트 product_name ("식품")
    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "unit", length = 20)
    private String unit;

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
