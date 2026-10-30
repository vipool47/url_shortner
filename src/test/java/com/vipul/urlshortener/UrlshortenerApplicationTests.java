package com.vipul.urlshortener;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import com.vipul.urlshortener.service.ResilientUrlThreatChecker;
import com.vipul.urlshortener.service.UrlThreatChecker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = "bitshort.url-safety.safe-browsing.api-key=test-api-key")
class UrlshortenerApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UrlThreatChecker urlThreatChecker;

    @Test
    void contextLoads() {
    }

    @Test
    void resilientThreatCheckerIsThePrimaryBean() {
        assertEquals(2, applicationContext.getBeansOfType(UrlThreatChecker.class).size());
        assertInstanceOf(ResilientUrlThreatChecker.class, urlThreatChecker);
    }
}
