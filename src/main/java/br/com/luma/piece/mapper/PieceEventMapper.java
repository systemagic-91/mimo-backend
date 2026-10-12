package br.com.luma.piece.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import br.com.luma.config.MapStructConfiguration;
import br.com.luma.piece.entity.PieceEventEntity;
import br.com.luma.shared.dto.PieceEventResponse;

@Mapper(config = MapStructConfiguration.class)
public interface PieceEventMapper {
    @Mapping(target = "pieceId", source = "piece.id")
    @Mapping(target = "actorUserId", source = "actor.id")
    PieceEventResponse toResponse(PieceEventEntity event);
}