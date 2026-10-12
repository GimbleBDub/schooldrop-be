package com.gimble.bus_stop.repositories;

import com.gimble.bus_stop.entities.User;
import com.gimble.bus_stop.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    // Find all kids belonging to a parent
    List<User> findByParentId(UUID parentId);

    boolean existsByRole(Role role);

    // Find all users by role
    List<User> findByRole(Role role);

    // Find by email verification token
    Optional<User> findByEmailVerificationToken(String token);

    @Query(value = """
            SELECT u FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (:query IS NULL
                   OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR u.phone LIKE CONCAT('%', CAST(:query AS string), '%'))
            ORDER BY u.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (:query IS NULL
                   OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))
                   OR u.phone LIKE CONCAT('%', CAST(:query AS string), '%'))
            """)
    Page<User> search(Role role, String query, Pageable pageable);

    long countByRole(Role role);
}
