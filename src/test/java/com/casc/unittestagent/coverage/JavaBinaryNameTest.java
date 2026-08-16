package com.casc.unittestagent.coverage;

import junit.framework.TestCase;

public class JavaBinaryNameTest extends TestCase {
    public void testKeepsTopLevelClassName() {
        assertEquals("org.jsoup.select.Evaluator",
                JavaBinaryName.fromSourceQualifiedName("org.jsoup.select.Evaluator", "org.jsoup.select"));
    }

    public void testConvertsNestedPsiNameToJvmBinaryName() {
        assertEquals("org.jsoup.select.Evaluator$Tag",
                JavaBinaryName.fromSourceQualifiedName("org.jsoup.select.Evaluator.Tag", "org.jsoup.select"));
        assertEquals("org/jsoup/select/Evaluator$Tag.class".replace('/', java.io.File.separatorChar),
                JavaBinaryName.classFileRelativePath("org.jsoup.select.Evaluator$Tag"));
    }
}
