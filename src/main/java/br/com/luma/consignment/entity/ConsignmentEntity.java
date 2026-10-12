package br.com.luma.consignment.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.shared.ConsignmentStatus;
import br.com.luma.shared.entity.VersionedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "consignments", uniqueConstraints = @UniqueConstraint(name = "uk_consignments_code", columnNames = "code"))
@NoArgsConstructor
public class ConsignmentEntity extends VersionedEntity {

    @Column(nullable = false, length = 32)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private SellerEntity seller;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsignmentStatus status;

    @Column(length = 500)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private UserEntity createdBy;

    @OneToMany(mappedBy = "consignment", fetch = FetchType.LAZY, cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    private List<ConsignmentItemEntity> items = new ArrayList<>();
}