package br.com.luma.piece.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.luma.shared.PieceStage;
import br.com.luma.shared.entity.VersionedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "pieces", uniqueConstraints = @UniqueConstraint(name = "uk_pieces_code", columnNames = "code"))
@NoArgsConstructor
public class PieceEntity extends VersionedEntity {

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(name = "value_cents", nullable = false)
    private long valueCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PieceStage status;

    @Column(name = "current_location", nullable = false, length = 120)
    private String currentLocation;

    @Column(name = "photo_id")
    private UUID photoId;

    @Column(length = 500)
    private String note;

    @Column(name = "factory_cost_cents")
    private Long factoryCostCents;

    @Column(name = "bath_cost_cents")
    private Long bathCostCents;

    @OneToMany(mappedBy = "piece", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    private List<PieceEventEntity> events = new ArrayList<>();
}