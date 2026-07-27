package com.aivle.be.product.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.product.controller.request.ProductRequest;
import com.aivle.be.product.controller.response.ProductResponse;
import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String productCode = request.productCode().trim();
        if (productRepository.existsByProductCode(productCode)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_CODE);
        }

        Product product = Product.create(productCode, request.productName().trim());
        return ProductResponse.from(productRepository.save(product));
    }

    public ProductResponse get(Long productId) {
        return ProductResponse.from(findById(productId));
    }

    public List<ProductResponse> getAll() {
        return productRepository.findAllByOrderByProductCodeAsc()
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional
    public ProductResponse update(Long productId, ProductRequest request) {
        Product product = findById(productId);
        String productCode = request.productCode().trim();

        if (productRepository.existsByProductCodeAndIdNot(productCode, productId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_CODE);
        }

        product.update(productCode, request.productName().trim());
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Long productId) {
        productRepository.delete(findById(productId));
    }

    private Product findById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }
}
