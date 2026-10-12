package br.com.luma.setting.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import br.com.luma.config.MapStructConfiguration;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.shared.dto.SettingsResponse;

@Mapper(config = MapStructConfiguration.class)
public interface SettingsMapper {
    @Mapping(target = "updatedById", source = "updatedBy.id")
    SettingsResponse toResponse(SettingsEntity settings);
}