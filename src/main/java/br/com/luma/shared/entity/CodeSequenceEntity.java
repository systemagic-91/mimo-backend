package br.com.luma.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "code_sequences", uniqueConstraints = @UniqueConstraint(name = "uk_code_sequences_scope_year", columnNames = { "scope", "sequence_year" }))
@NoArgsConstructor
public class CodeSequenceEntity extends BaseEntity {

    @Column(nullable = false, length = 40)
    private String scope;

    @Column(name = "sequence_year", nullable = false)
    private short sequenceYear;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}