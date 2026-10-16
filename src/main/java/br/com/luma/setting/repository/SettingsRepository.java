package br.com.luma.setting.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import br.com.luma.setting.entity.SettingsEntity;
import jakarta.persistence.LockModeType;

public interface SettingsRepository extends JpaRepository<SettingsEntity, UUID> {
    Optional<SettingsEntity> findFirstBySingletonKeyTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select settings from SettingsEntity settings where settings.singletonKey = true")
    Optional<SettingsEntity> findSingletonForUpdate();
}