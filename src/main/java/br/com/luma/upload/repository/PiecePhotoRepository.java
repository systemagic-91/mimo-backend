package br.com.luma.upload.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.upload.entity.PiecePhotoEntity;

public interface PiecePhotoRepository extends JpaRepository<PiecePhotoEntity, UUID> {

    @Modifying
    @Query(value = """
            DELETE FROM piece_photos photo
            WHERE photo.created_at < :cutoff
              AND NOT EXISTS (SELECT 1 FROM pieces piece WHERE piece.photo_id = photo.id)
            """, nativeQuery = true)
    int deleteOrphansCreatedBefore(@Param("cutoff") Instant cutoff);
}
