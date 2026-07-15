package com.aivle.be.warehouse.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "warehouse")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "warehouse_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public static Warehouse create(
            String name,
            Integer width,
            Integer height,
            Long userId
    ) {
        return Warehouse.builder()
                .name(name)
                .width(width)
                .height(height)
                .userId(userId)
                .build();
    }

    public void update(
            String name,
            Integer width,
            Integer height
    ) {
        this.name = name;
        this.width = width;
        this.height = height;
    }

}