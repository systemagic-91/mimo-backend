package br.com.luma.consignment.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import br.com.luma.config.MapStructConfiguration;
import br.com.luma.consignment.entity.ConsignmentEntity;
import br.com.luma.shared.dto.ConsignmentResponse;

@Mapper(config = MapStructConfiguration.class)
public interface ConsignmentMapper {
    @Mapping(target = "sellerId", source = "seller.id")
    @Mapping(target = "createdById", source = "createdBy.id")
    ConsignmentResponse toResponse(ConsignmentEntity consignment);
}