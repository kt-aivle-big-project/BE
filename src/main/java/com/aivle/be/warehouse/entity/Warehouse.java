package com.aivle.be.warehouse.entity;

import com.aivle.be.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "warehouse_layout",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_warehouse_layout_user_source_template",
                        columnNames = {"user_id", "source_template_id"}
                ),
                @UniqueConstraint(
                        name = "uk_warehouse_layout_guest_source_template",
                        columnNames = {"guest_session_id", "source_template_id"}
                )
        }
)
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

    @Column(length = 200)
    private String location;

    /** 설명 */
    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WarehouseStatus status = WarehouseStatus.ACTIVE;

    @Column(name = "is_shared")
    private Boolean shared = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_session_id", length = 36)
    private String guestSessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_template_id")
    private Warehouse sourceTemplate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static Warehouse create(
            String name,
            Integer width,
            Integer height,
            User user
    ) {
        return create(name, width, height, user, null, null, null);
    }

    public static Warehouse create(
            String name,
            Integer width,
            Integer height,
            User user,
            String location,
            String description,
            WarehouseStatus status
    ) {
        Warehouse warehouse = new Warehouse();
        warehouse.name = name;
        warehouse.width = width;
        warehouse.height = height;
        warehouse.user = user;
        warehouse.location = location;
        warehouse.description = description;
        warehouse.status = status == null ? WarehouseStatus.ACTIVE : status;
        warehouse.createdAt = LocalDateTime.now();
        warehouse.updatedAt = warehouse.createdAt;
        return warehouse;
    }

    public static Warehouse createPersonalCopy(
            Warehouse sourceTemplate,
            User user
    ) {
        Warehouse warehouse = create(
                sourceTemplate.name,
                sourceTemplate.width,
                sourceTemplate.height,
                user,
                sourceTemplate.location,
                sourceTemplate.description,
                sourceTemplate.status
        );
        warehouse.sourceTemplate = sourceTemplate;
        warehouse.shared = false;
        return warehouse;
    }

    public static Warehouse createGuestPersonalCopy(
            Warehouse sourceTemplate,
            String guestSessionId
    ) {
        Warehouse warehouse = new Warehouse();
        warehouse.name = sourceTemplate.name;
        warehouse.width = sourceTemplate.width;
        warehouse.height = sourceTemplate.height;
        warehouse.location = sourceTemplate.location;
        warehouse.description = sourceTemplate.description;
        warehouse.status = sourceTemplate.status;
        warehouse.shared = false;
        warehouse.user = null;
        warehouse.guestSessionId = guestSessionId;
        warehouse.sourceTemplate = sourceTemplate;
        warehouse.createdAt = LocalDateTime.now();
        warehouse.updatedAt = warehouse.createdAt;
        return warehouse;
    }

    public void update(
            String name,
            Integer width,
            Integer height
    ) {
        update(name, width, height, null, null, null);
    }

    public void update(
            String name,
            Integer width,
            Integer height,
            String location,
            String description,
            WarehouseStatus status
    ) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        if (width != null) {
            this.width = width;
        }
        if (height != null) {
            this.height = height;
        }
        if (location != null) {
            this.location = location;
        }
        if (description != null) {
            this.description = description;
        }
        if (status != null) {
            this.status = status;
        }

        this.updatedAt = LocalDateTime.now();
    }

    public boolean isShared() {
        return Boolean.TRUE.equals(shared);
    }

    public void markShared() {
        this.shared = true;
    }

    /** 이 사용자가 볼 수 있는 창고인가. */
    public boolean isVisibleTo(Long userId) {
        return isShared() || isOwnedBy(userId);
    }

    public boolean isOwnedBy(Long userId) {
        return userId != null
                && user != null
                && userId.equals(user.getId());
    }

    public boolean isOwnedByGuest(String guestSessionId) {
        return guestSessionId != null
                && guestSessionId.equals(this.guestSessionId);
    }

    public enum WarehouseStatus {
        ACTIVE,
        MAINTENANCE,
        INACTIVE
    }
}
