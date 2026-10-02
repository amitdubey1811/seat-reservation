package com.amitdubey.seats.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByHandle(String handle);

    /**
     * Creates the user unless the handle is already taken.
     *
     * <p>Native, and {@code ON CONFLICT DO NOTHING} rather than "try to save and catch
     * the constraint violation". That matters with JPA: a
     * {@code DataIntegrityViolationException} inside a transaction marks it
     * rollback-only, so the follow-up read needed to recover from the race would itself
     * fail. Letting Postgres absorb the conflict keeps the whole thing in one healthy
     * transaction and needs no exception handling at all.
     *
     * <p>This is not hypothetical: the burst script mints tokens for thousands of users
     * concurrently, and duplicates of the same handle are expected.
     *
     * @return 1 if this call created the user, 0 if someone else already had
     */
    @Modifying
    @Query(value = """
            INSERT INTO app_users (id, handle, role, created_at)
            VALUES (:id, :handle, :role, now())
            ON CONFLICT (handle) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("handle") String handle,
                       @Param("role") String role);

    @Modifying
    @Query("update AppUser u set u.role = :role where u.handle = :handle and u.role <> :role")
    int promoteRole(@Param("handle") String handle, @Param("role") UserRole role);
}
