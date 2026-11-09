package com.vipul.urlshortener.repository;

import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.entity.SafeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    List<UrlMapping> findAllByLongUrl(String longUrl);

    @Query("""
            SELECT u FROM UrlMapping u
            WHERE u.safeStatus = :status
              AND (u.expiresAt IS NULL OR u.expiresAt > :now)
              AND u.createdAt >= :createdAfter
            ORDER BY u.createdAt ASC
            """)
    List<UrlMapping> findOldestPending(
            @Param("status") SafeStatus status,
            @Param("now") LocalDateTime now,
            @Param("createdAfter") LocalDateTime createdAfter,
            org.springframework.data.domain.Pageable pageable
    );

    boolean existsBySafeStatus(SafeStatus safeStatus);

    @Modifying
    @Transactional
    @Query("""
            UPDATE UrlMapping u
            SET u.safeStatus = :newStatus, u.safeCheckedAt = :checkedAt
            WHERE u.id = :id AND u.safeStatus = :expectedStatus
            """)
    int updateSafeStatusIfExpected(
            @Param("id") Long id,
            @Param("expectedStatus") SafeStatus expectedStatus,
            @Param("newStatus") SafeStatus newStatus,
            @Param("checkedAt") LocalDateTime checkedAt
    );

    @Modifying
    @Transactional
    @Query("DELETE FROM UrlMapping u WHERE u.expiresAt IS NOT NULL AND u.expiresAt < :now")
    int deleteExpiredLinks(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("""
            DELETE FROM UrlMapping u
            WHERE u.safeStatus = :status AND u.createdAt < :createdBefore
            """)
    int deleteStuckPendingLinks(
            @Param("status") SafeStatus status,
            @Param("createdBefore") LocalDateTime createdBefore
    );
}