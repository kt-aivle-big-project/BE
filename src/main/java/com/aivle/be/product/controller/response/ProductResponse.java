package com.aivle.be.product.controller.response;

import com.aivle.be.product.entity.Product;

public record ProductResponse(
        Long id,
        String productCode,
        String productName
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getProductCode(),
                product.getProductName()
        );
    }
}
