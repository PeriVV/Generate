package com.casc.unittestagent.llm;

import junit.framework.TestCase;

public class DiagnosticRedactorTest extends TestCase {
    public void testKeyBearerAndUrlCredentialsNeverEnterReports() {
        String key = "private-key-with-$-specials";
        var redactor = new DiagnosticRedactor(key);
        String safe = redactor.clean("error " + key + " Bearer other-secret https://user:pass@intranet:8443/v1?token=query-secret#fragment");
        assertFalse(safe, safe.contains(key)); assertFalse(safe, safe.contains("other-secret"));
        assertFalse(safe, safe.contains("pass@")); assertFalse(safe, safe.contains("query-secret"));
        assertTrue(safe, safe.contains("intranet:8443/v1"));
    }
    public void testQuotedErrorFieldsAndEncodedQueryAreRemoved() {
        var redactor = new DiagnosticRedactor("configured");
        String safe = redactor.detail("{\"api_key\":\"other-value\",\"access_token\":\"value-token\"} "
                + "http://host/path?password=url%20secret\nnext");
        assertFalse(safe, safe.contains("other-value")); assertFalse(safe, safe.contains("value-token"));
        assertFalse(safe, safe.contains("url%20secret")); assertFalse(safe.contains("\n"));
    }
    public void testLongUnparseableAddressAndErrorRemainBounded() {
        var redactor = new DiagnosticRedactor("secret");
        assertEquals("[地址无法解析]", redactor.endpoint("invalid secret"));
        assertEquals(500, redactor.detail("x".repeat(1000)).length());
    }
    public void testTruncatedLongCredentialPrefixesAreCompletelyHidden() {
        String key = "private-" + "long-credential-value".repeat(20);
        var redactor = new DiagnosticRedactor(key);
        String safe = redactor.detail("request-id=" + key.substring(0, 160));
        assertFalse(safe, safe.contains(key.substring(0, 16)));
        assertFalse(safe, safe.contains("long-credential-value"));
        assertEquals("request-id=[密钥已隐藏]", safe);
    }
}
