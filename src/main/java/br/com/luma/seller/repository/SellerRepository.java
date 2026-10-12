package br.com.luma.seller.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.shared.SellerStatus;

public interface SellerRepository extends JpaRepository<SellerEntity, UUID> {
    List<SellerEntity> findAllByStatusOrderByNameAsc(SellerStatus status);
}