package br.com.luma.seller.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.seller.entity.SellerEntity;
import br.com.luma.shared.SellerStatus;
import jakarta.persistence.LockModeType;

public interface SellerRepository extends JpaRepository<SellerEntity, UUID> {
    List<SellerEntity> findAllByStatusOrderByNameAsc(SellerStatus status);

    @Query("""
            select seller from SellerEntity seller
            where (:status is null or seller.status = :status)
              and (:query is null or lower(seller.name) like lower(concat('%', :query, '%'))
                   or seller.phone like concat('%', :query, '%'))
            """)
    Page<SellerEntity> search(@Param("status") SellerStatus status, @Param("query") String query,
            Pageable pageable);

        java.util.Optional<SellerEntity> findByIdAndStatus(UUID id, SellerStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select seller from SellerEntity seller where seller.id = :id")
    java.util.Optional<SellerEntity> findByIdForUpdate(@Param("id") UUID id);
}