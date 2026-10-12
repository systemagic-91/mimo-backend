package br.com.luma.shared.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@MappedSuperclass
public abstract class VersionedEntity extends TimestampedEntity {

    @Version
    @Column(nullable = false)
    private long version;
}