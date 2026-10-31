package br.com.luma.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.luma.auth.entity.UserEntity;
import br.com.luma.shared.Role;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmailIgnoreCase(String email);

    @Query("""
            select u from UserEntity u
            where (:role is null or u.role = :role)
              and (:active is null or u.active = :active)
              and (cast(:query as String) is null
                   or lower(u.name) like lower(concat('%', cast(:query as String), '%'))
                   or lower(u.email) like lower(concat('%', cast(:query as String), '%')))
            """)
    Page<UserEntity> search(@Param("role") Role role, @Param("active") Boolean active,
            @Param("query") String query, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.role = :role and u.active = true order by u.id")
    List<UserEntity> findActiveByRoleForUpdate(@Param("role") Role role);
}
