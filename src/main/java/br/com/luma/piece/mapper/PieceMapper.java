package br.com.luma.piece.mapper;

import org.mapstruct.Mapper;

import br.com.luma.config.MapStructConfiguration;
import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.shared.dto.PieceResponse;

@Mapper(config = MapStructConfiguration.class)
public interface PieceMapper {
    PieceResponse toResponse(PieceEntity piece);
}