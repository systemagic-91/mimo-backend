package br.com.luma.consignment.entity;

import java.time.Instant;

import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.shared.ConsignmentItemStatus;
import br.com.luma.shared.entity.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "consignment_items")
@NoArgsConstructor
public class ConsignmentItemEntity extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consignment_id", nullable = false)
    private ConsignmentEntity consignment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "piece_id", nullable = false)
    private PieceEntity piece;

    @Column(name = "code_snapshot", nullable = false, length = 32)
    private String codeSnapshot;

    @Column(name = "type_snapshot", nullable = false, length = 80)
    private String typeSnapshot;

    @Column(name = "value_cents_snapshot", nullable = false)
    private long valueCentsSnapshot;

    @Column(name = "commission_rate_snapshot", nullable = false)
    private int commissionRateSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConsignmentItemStatus status;

    @Column(name = "sold_at")
    private Instant soldAt;

    @Column(name = "returned_at")
    private Instant returnedAt;
}