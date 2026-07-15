package com.aivle.be.warehouse.entity;

import com.aivle.be.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "warehouse_layout")
@Getter
@NoArgsConstructor
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private Integer width;

    private Integer height;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public static Warehouse create(
            String name,
            Integer width,
            Integer height,
            User user
    ) {
        Warehouse warehouse = new Warehouse();
        warehouse.name = name;
        warehouse.width = width;
        warehouse.height = height;
        warehouse.user = user;
        return warehouse;
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