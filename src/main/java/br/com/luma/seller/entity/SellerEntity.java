package br.com.luma.seller.entity;

import br.com.luma.shared.SellerStatus;
import br.com.luma.shared.entity.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "sellers")
@NoArgsConstructor
public class SellerEntity extends TimestampedEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 40)
    private String phone = "";

    @Column(name = "commission_rate", nullable = false)
    private int commissionRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SellerStatus status;
}