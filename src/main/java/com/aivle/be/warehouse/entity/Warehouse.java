package com.aivle.be.warehouse.entity;

import com.aivle.be.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    /** 소재지. 화면 목록에서 보여준다. */
    @Column(length = 200)
    private String location;

    /** 설명 */
    @Column(length = 500)
    private String description;

    /**
     * 운영 상태.
     *
     * 점검 중이거나 비활성인 창고는 시뮬레이션 대상에서 제외할 수 있다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private WarehouseStatus status = WarehouseStatus.ACTIVE;

    /**
     * 공용 창고 여부.
     *
     * <p>시드로 넣는 기본 창고 3개는 모두에게 보이고 수정·삭제할 수 없다.
     * 사용자가 만든 창고는 만든 사람에게만 보인다.
     *
     * <p>기존 행에는 값이 없을 수 있어 null 을 허용하고, null 은 개인 창고로 본다.
     */
    @Column(name = "is_shared")
    private Boolean shared = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

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

    public void update(
            String name,
            Integer width,
            Integer height
    ) {
        update(name, width, height, null, null, null);
    }

    /**
     * 창고 정보를 고친다.
     *
     * null 로 들어온 항목은 기존 값을 유지한다.
     * 화면에서 일부만 수정하는 경우가 있기 때문이다.
     */
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

    /** 공용 창고인가. 값이 없으면 개인 창고로 본다. */
    public boolean isShared() {
        return Boolean.TRUE.equals(shared);
    }

    /**
     * 공용 창고로 표시한다.
     *
     * <p>앱을 처음 켤 때 넣는 기본 창고에만 쓴다.
     * 화면에서 만든 창고는 항상 개인 창고다.
     */
    public void markShared() {
        this.shared = true;
    }

    /** 이 사용자가 볼 수 있는 창고인가. */
    public boolean isVisibleTo(Long userId) {
        return isShared() || (userId != null && userId.equals(user.getId()));
    }

    /**
     * 창고 운영 상태.
     *
     * 화면 표기
     *   ACTIVE       운영 중
     *   MAINTENANCE  점검 중
     *   INACTIVE     비활성
     */
    public enum WarehouseStatus {
        ACTIVE,
        MAINTENANCE,
        INACTIVE
    }
}
