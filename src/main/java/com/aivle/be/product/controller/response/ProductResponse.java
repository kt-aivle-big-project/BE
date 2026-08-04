package com.aivle.be.product.controller.response;

import com.aivle.be.product.entity.Product;

public record ProductResponse(
        Long id,
        String productCode,
        String productName,
        String category,
        String unit,
        Integer unitsPerBox,
        String barcode,
        String temperatureZone,
        Boolean fragile
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getProductCode(),
                product.getProductName(),
                product.getCategory(),
                product.getUnit(),
                product.getUnitsPerBox(),
                product.getBarcode(),
                product.getTemperatureZone(),
                product.getFragile()
        );
    }
}
