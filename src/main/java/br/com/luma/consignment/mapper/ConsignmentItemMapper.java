package br.com.luma.consignment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import br.com.luma.config.MapStructConfiguration;
import br.com.luma.consignment.entity.ConsignmentItemEntity;
import br.com.luma.shared.dto.ConsignmentItemResponse;

@Mapper(config = MapStructConfiguration.class)
public interface ConsignmentItemMapper {
    @Mapping(target = "consignmentId", source = "consignment.id")
    @Mapping(target = "pieceId", source = "piece.id")
    ConsignmentItemResponse toResponse(ConsignmentItemEntity item);
}