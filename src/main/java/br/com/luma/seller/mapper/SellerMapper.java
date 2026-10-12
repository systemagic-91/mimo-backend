package br.com.luma.seller.mapper;

import org.mapstruct.Mapper;

import br.com.luma.config.MapStructConfiguration;
import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.shared.dto.SellerResponse;

@Mapper(config = MapStructConfiguration.class)
public interface SellerMapper {
    SellerResponse toResponse(SellerEntity seller);
}