package br.com.luma.setting.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.setting.entity.SettingsEntity;

public interface SettingsRepository extends JpaRepository<SettingsEntity, UUID> {
    Optional<SettingsEntity> findFirstBySingletonKeyTrue();
}