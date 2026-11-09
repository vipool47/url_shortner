package com.vipul.urlshortener.repository;

import com.vipul.urlshortener.entity.SafeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class UrlMappingRepositoryTest {

    @Test
    void conditionalStatusUpdateIsTransactionalAndReturnsAffectedRowCount() throws Exception {
        Method method = UrlMappingRepository.class.getMethod(
                "updateSafeStatusIfExpected",
                Long.class,
                SafeStatus.class,
                SafeStatus.class,
                LocalDateTime.class
        );

        assertEquals(int.class, method.getReturnType());
        assertNotNull(method.getAnnotation(Modifying.class));
        assertNotNull(method.getAnnotation(Transactional.class));
        assertTrue(method.getAnnotation(Query.class).value().contains("u.safeStatus = :expectedStatus"));
    }
}
