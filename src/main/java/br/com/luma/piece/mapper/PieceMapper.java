package br.com.luma.piece.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import br.com.luma.config.MapStructConfiguration;
import br.com.luma.piece.entity.PieceEntity;
import br.com.luma.piece.service.PieceCosts;
import br.com.luma.shared.dto.PieceResponse;
import br.com.luma.upload.PhotoUrls;

@Mapper(config = MapStructConfiguration.class, imports = { PhotoUrls.class, PieceCosts.class })
public interface PieceMapper {
    @Mapping(target = "photoUrl", expression = "java(PhotoUrls.of(piece.getPhotoId()))")
    @Mapping(target = "totalCostCents",
            expression = "java(PieceCosts.total(piece.getFactoryCostCents(), piece.getBathCostCents()))")
    PieceResponse toResponse(PieceEntity piece);
}
