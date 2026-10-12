package br.com.luma.setting.entity;

import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "settings", uniqueConstraints = @UniqueConstraint(name = "uk_settings_singleton_key", columnNames = "singleton_key"))
@NoArgsConstructor
public class SettingsEntity extends BaseEntity {

    @Column(name = "singleton_key", nullable = false)
    private boolean singletonKey = true;

    @Column(name = "commission_min", nullable = false)
    private int commissionMin;

    @Column(name = "commission_max", nullable = false)
    private int commissionMax;

    @Column(name = "commission_step", nullable = false)
    private int commissionStep;

    @Column(name = "default_commission_rate", nullable = false)
    private int defaultCommissionRate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payment_terms", nullable = false, columnDefinition = "jsonb")
    private List<Integer> paymentTerms;

    @Column(name = "default_consignment_days", nullable = false)
    private int defaultConsignmentDays;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private UserEntity updatedBy;
}