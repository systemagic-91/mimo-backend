package br.com.luma.auth.mapper;

import org.mapstruct.Mapper;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.config.MapStructConfiguration;
import br.com.luma.shared.dto.UserResponse;

@Mapper(config = MapStructConfiguration.class)
public interface UserMapper {
    UserResponse toResponse(UserEntity user);
}