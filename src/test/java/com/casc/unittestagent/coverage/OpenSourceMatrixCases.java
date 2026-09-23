package com.casc.unittestagent.coverage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 开源矩阵用例注册表。
 * 覆盖率闭环矩阵与规则生成流水线测试共用同一份真实仓库目标：
 * repo 对应 tmp/oss/&lt;repo&gt;（prepare-oss-matrix.sh 准备），moduleDir 支持多模块项目。
 */
final class OpenSourceMatrixCases {
    private OpenSourceMatrixCases() {
    }

    static List<Case> all() {
        return List.of(
                commonsCodecHex(),
                commonsIoFilenameUtils(),
                commonsLangNumberUtils(),
                commonsTextJaroWinkler(),
                guavaStringsRepeat(),
                jacksonDatabindTypeParser(),
                hikariCpUtilityElf(),
                jacksonCoreNumberInput(),
                springPetclinicPetValidator(),
                mybatis3GenericTokenParser());
    }

    private static Case mybatis3GenericTokenParser() {
        return new Case(
                "mybatis-3",
                "",
                "org/apache/ibatis/parsing/GenericTokenParser.java",
                "public String parse(String text)",
                "org.apache.ibatis.parsing.GenericTokenParser",
                """
                package org.apache.ibatis.parsing;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UnitMindMatrixInitialTest {
                    @Test void passesTextWithoutTokensThrough() {
                        GenericTokenParser parser =
                                new GenericTokenParser("#{", "}", content -> "[" + content + "]");
                        assertEquals("hello", parser.parse("hello"));
                    }
                }
                """,
                """
                package org.apache.ibatis.parsing;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UnitMindMatrixImprovedTest {
                    private GenericTokenParser parser() {
                        return new GenericTokenParser("#{", "}", content -> "[" + content + "]");
                    }
                    @Test void passesTextWithoutTokensThrough() {
                        assertEquals("hello", parser().parse("hello"));
                    }
                    @Test void nullTextReturnsEmpty() {
                        assertEquals("", parser().parse(null));
                    }
                    @Test void emptyTextReturnsEmpty() {
                        assertEquals("", parser().parse(""));
                    }
                    @Test void replacesTokenWithHandlerOutput() {
                        assertEquals("a[b]c", parser().parse("a#{b}c"));
                    }
                    @Test void escapedOpenTokenIsKeptLiteral() {
                        assertEquals("#{b}", parser().parse("\\\\#{b}"));
                    }
                    @Test void unclosedTokenKeepsTailLiteral() {
                        assertEquals("a#{b", parser().parse("a#{b"));
                    }
                }
                """);
    }

    private static Case jacksonCoreNumberInput() {
        return new Case(
                "jackson-core",
                "",
                "com/fasterxml/jackson/core/io/NumberInput.java",
                "public static boolean inLongRange(String s, boolean negative)",
                "com.fasterxml.jackson.core.io.NumberInput",
                """
                package com.fasterxml.jackson.core.io;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindMatrixInitialTest {
                    @Test void shortNumberStaysInLongRange() {
                        assertTrue(NumberInput.inLongRange("123", false));
                    }
                }
                """,
                """
                package com.fasterxml.jackson.core.io;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindMatrixImprovedTest {
                    @Test void shortNumberStaysInLongRange() {
                        assertTrue(NumberInput.inLongRange("123", false));
                    }
                    @Test void equalLengthBelowMaxStaysInRange() {
                        assertTrue(NumberInput.inLongRange("9223372036854775806", false));
                    }
                    @Test void equalLengthAboveMaxExceedsRange() {
                        assertFalse(NumberInput.inLongRange("9223372036854775808", false));
                    }
                    @Test void longerNumberExceedsRange() {
                        assertFalse(NumberInput.inLongRange("92233720368547758071", false));
                    }
                    @Test void negativeMinValueStaysInRange() {
                        assertTrue(NumberInput.inLongRange("9223372036854775808", true));
                    }
                    @Test void negativeBelowMinExceedsRange() {
                        assertFalse(NumberInput.inLongRange("9223372036854775809", true));
                    }
                }
                """);
    }

    private static Case springPetclinicPetValidator() {
        return new Case(
                "spring-petclinic",
                "",
                "org/springframework/samples/petclinic/owner/PetValidator.java",
                "public void validate(Object obj, Errors errors)",
                "org.springframework.samples.petclinic.owner.PetValidator",
                """
                package org.springframework.samples.petclinic.owner;
                import java.time.LocalDate;
                import org.junit.jupiter.api.Test;
                import org.springframework.validation.BeanPropertyBindingResult;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                class UnitMindMatrixInitialTest {
                    @Test void acceptsCompletePet() {
                        Pet pet = new Pet();
                        pet.setName("Rex");
                        pet.setBirthDate(LocalDate.of(2020, 1, 1));
                        pet.setType(new PetType());
                        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
                        new PetValidator().validate(pet, errors);
                        assertFalse(errors.hasErrors());
                    }
                }
                """,
                """
                package org.springframework.samples.petclinic.owner;
                import java.time.LocalDate;
                import org.junit.jupiter.api.Test;
                import org.springframework.validation.BeanPropertyBindingResult;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindMatrixImprovedTest {
                    @Test void acceptsCompletePet() {
                        Pet pet = new Pet();
                        pet.setName("Rex");
                        pet.setBirthDate(LocalDate.of(2020, 1, 1));
                        pet.setType(new PetType());
                        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
                        new PetValidator().validate(pet, errors);
                        assertFalse(errors.hasErrors());
                    }
                    @Test void rejectsMissingName() {
                        Pet pet = new Pet();
                        pet.setBirthDate(LocalDate.of(2020, 1, 1));
                        pet.setType(new PetType());
                        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
                        new PetValidator().validate(pet, errors);
                        assertTrue(errors.hasFieldErrors("name"));
                    }
                    @Test void rejectsNewPetWithoutType() {
                        Pet pet = new Pet();
                        pet.setName("Rex");
                        pet.setBirthDate(LocalDate.of(2020, 1, 1));
                        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
                        new PetValidator().validate(pet, errors);
                        assertTrue(errors.hasFieldErrors("type"));
                    }
                    @Test void rejectsMissingBirthDate() {
                        Pet pet = new Pet();
                        pet.setName("Rex");
                        pet.setType(new PetType());
                        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(pet, "pet");
                        new PetValidator().validate(pet, errors);
                        assertTrue(errors.hasFieldErrors("birthDate"));
                    }
                }
                """);
    }

    private static Case commonsCodecHex() {
        return new Case(
                "commons-codec",
                "",
                "org/apache/commons/codec/binary/Hex.java",
                "protected static int toDigit(final char ch, final int index)",
                "org.apache.commons.codec.binary.Hex",
                """
                package org.apache.commons.codec.binary;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertArrayEquals;
                class UnitMindMatrixInitialTest {
                    @Test void decodesLowerCaseHex() throws Exception {
                        assertArrayEquals(new byte[]{0x0F, (byte) 0xAB}, Hex.decodeHex("0fab".toCharArray()));
                    }
                }
                """,
                """
                package org.apache.commons.codec.binary;
                import org.apache.commons.codec.DecoderException;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertArrayEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class UnitMindMatrixImprovedTest {
                    @Test void decodesLowerCaseHex() throws Exception {
                        assertArrayEquals(new byte[]{0x0F, (byte) 0xAB}, Hex.decodeHex("0fab".toCharArray()));
                    }
                    @Test void decodesUpperCaseHex() throws Exception {
                        assertArrayEquals(new byte[]{0x0A}, Hex.decodeHex("0A".toCharArray()));
                    }
                    @Test void rejectsIllegalCharacter() {
                        assertThrows(DecoderException.class, () -> Hex.decodeHex("0g".toCharArray()));
                    }
                }
                """);
    }

    private static Case commonsIoFilenameUtils() {
        return new Case(
                "commons-io",
                "",
                "org/apache/commons/io/FilenameUtils.java",
                "public static String getName(final String fileName)",
                "org.apache.commons.io.FilenameUtils",
                """
                package org.apache.commons.io;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UnitMindMatrixInitialTest {
                    @Test void returnsSegmentAfterLastSeparator() {
                        assertEquals("c.txt", FilenameUtils.getName("a/b/c.txt"));
                    }
                }
                """,
                """
                package org.apache.commons.io;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNull;
                class UnitMindMatrixImprovedTest {
                    @Test void returnsSegmentAfterLastSeparator() {
                        assertEquals("c.txt", FilenameUtils.getName("a/b/c.txt"));
                    }
                    @Test void handlesBackslashSeparator() {
                        assertEquals("c.txt", FilenameUtils.getName("a\\\\b\\\\c.txt"));
                    }
                    @Test void handlesMissingSeparator() {
                        assertEquals("abc", FilenameUtils.getName("abc"));
                    }
                    @Test void handlesTrailingSeparator() {
                        assertEquals("", FilenameUtils.getName("a/b/"));
                    }
                    @Test void handlesNullAndEmpty() {
                        assertNull(FilenameUtils.getName(null));
                        assertEquals("", FilenameUtils.getName(""));
                    }
                }
                """);
    }

    private static Case commonsLangNumberUtils() {
        return new Case(
                "commons-lang",
                "",
                "org/apache/commons/lang3/math/NumberUtils.java",
                "public static boolean isCreatable(final String str)",
                "org.apache.commons.lang3.math.NumberUtils",
                """
                package org.apache.commons.lang3.math;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindMatrixInitialTest {
                    @Test void acceptsDecimalInteger() {
                        assertTrue(NumberUtils.isCreatable("123"));
                        assertFalse(NumberUtils.isCreatable("abc"));
                    }
                }
                """,
                """
                package org.apache.commons.lang3.math;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindMatrixImprovedTest {
                    @Test void acceptsDecimalInteger() {
                        assertTrue(NumberUtils.isCreatable("123"));
                    }
                    @Test void rejectsNullEmptyAndBlank() {
                        assertFalse(NumberUtils.isCreatable(null));
                        assertFalse(NumberUtils.isCreatable(""));
                        assertFalse(NumberUtils.isCreatable(" 1"));
                    }
                    @Test void acceptsHexAndOctalPrefixes() {
                        assertTrue(NumberUtils.isCreatable("0x1F"));
                        assertTrue(NumberUtils.isCreatable("017"));
                    }
                    @Test void acceptsExponentAndTypeSuffix() {
                        assertTrue(NumberUtils.isCreatable("1.5e3"));
                        assertTrue(NumberUtils.isCreatable("1L"));
                        assertTrue(NumberUtils.isCreatable(".5"));
                    }
                    @Test void rejectsTrailingTypeOnly() {
                        assertFalse(NumberUtils.isCreatable("l"));
                        assertFalse(NumberUtils.isCreatable("0x"));
                    }
                }
                """);
    }

    private static Case commonsTextJaroWinkler() {
        return new Case(
                "commons-text",
                "",
                "org/apache/commons/text/similarity/JaroWinklerSimilarity.java",
                "public Double apply(final CharSequence left, final CharSequence right)",
                "org.apache.commons.text.similarity.JaroWinklerSimilarity",
                """
                package org.apache.commons.text.similarity;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UnitMindMatrixInitialTest {
                    @Test void identicalStringsScoreOne() {
                        assertEquals(1.0d, new JaroWinklerSimilarity().apply("foo", "foo"), 1e-9);
                    }
                }
                """,
                """
                package org.apache.commons.text.similarity;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class UnitMindMatrixImprovedTest {
                    @Test void identicalStringsScoreOne() {
                        assertEquals(1.0d, new JaroWinklerSimilarity().apply("foo", "foo"), 1e-9);
                    }
                    @Test void emptyInputsScoreOne() {
                        assertEquals(1.0d, new JaroWinklerSimilarity().apply("", ""), 1e-9);
                    }
                    @Test void disjointInputsScoreZero() {
                        assertEquals(0.0d, new JaroWinklerSimilarity().apply("abc", "xyz"), 1e-9);
                    }
                    @Test void typosReduceScore() {
                        double score = new JaroWinklerSimilarity().apply("apache", "arache");
                        assertTrue(score > 0.5d && score < 1.0d, "score=" + score);
                    }
                    @Test void sharedPrefixBoostsScore() {
                        JaroWinklerSimilarity similarity = new JaroWinklerSimilarity();
                        double withSharedPrefix = similarity.apply("junit", "junitx");
                        double withoutSharedPrefix = similarity.apply("unitj", "xunit");
                        assertTrue(withSharedPrefix > withoutSharedPrefix,
                                withSharedPrefix + " vs " + withoutSharedPrefix);
                    }
                }
                """);
    }

    private static Case guavaStringsRepeat() {
        return new Case(
                "guava",
                "guava",
                "com/google/common/base/Strings.java",
                "public static String repeat(String string, int count)",
                "com.google.common.base.Strings",
                """
                package com.google.common.base;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UnitMindMatrixInitialTest {
                    @Test void repeatsSimpleString() {
                        assertEquals("aaa", Strings.repeat("a", 3));
                    }
                }
                """,
                """
                package com.google.common.base;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class UnitMindMatrixImprovedTest {
                    @Test void repeatsSimpleString() {
                        assertEquals("aaa", Strings.repeat("a", 3));
                    }
                    @Test void repeatsLongerString() {
                        assertEquals("abab", Strings.repeat("ab", 2));
                    }
                    @Test void zeroCountReturnsEmpty() {
                        assertEquals("", Strings.repeat("a", 0));
                    }
                    @Test void rejectsNegativeCount() {
                        assertThrows(IllegalArgumentException.class, () -> Strings.repeat("a", -1));
                    }
                    @Test void rejectsNullString() {
                        assertThrows(NullPointerException.class, () -> Strings.repeat(null, 2));
                    }
                }
                """);
    }

    private static Case jacksonDatabindTypeParser() {
        return new Case(
                "jackson-databind",
                "",
                "com/fasterxml/jackson/databind/type/TypeParser.java",
                "protected JavaType parseType(MyTokenizer tokens, int nestingAllowed)",
                "com.fasterxml.jackson.databind.type.TypeParser",
                """
                package com.fasterxml.jackson.databind.type;
                import com.fasterxml.jackson.databind.JavaType;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UnitMindMatrixInitialTest {
                    @Test void parsesSimpleClassType() {
                        JavaType type = new TypeParser(TypeFactory.defaultInstance()).parse("java.lang.String");
                        assertEquals(String.class, type.getRawClass());
                    }
                }
                """,
                """
                package com.fasterxml.jackson.databind.type;
                import com.fasterxml.jackson.databind.JavaType;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class UnitMindMatrixImprovedTest {
                    @Test void parsesSimpleClassType() {
                        JavaType type = new TypeParser(TypeFactory.defaultInstance()).parse("java.lang.String");
                        assertEquals(String.class, type.getRawClass());
                    }
                    @Test void parsesGenericCollectionType() {
                        JavaType type = new TypeParser(TypeFactory.defaultInstance())
                .parse("java.util.List<java.lang.String>");
                        assertEquals(java.util.List.class, type.getRawClass());
                    }
                    @Test void parsesGenericMapType() {
                        JavaType type = new TypeParser(TypeFactory.defaultInstance())
                .parse("java.util.Map<java.lang.String,java.lang.Integer>");
                        assertEquals(java.util.Map.class, type.getRawClass());
                    }
                    @Test void rejectsEmptyInput() {
                        assertThrows(IllegalArgumentException.class,
                                () -> new TypeParser(TypeFactory.defaultInstance()).parse(""));
                    }
                    @Test void rejectsUnknownClass() {
                        assertThrows(IllegalArgumentException.class,
                                () -> new TypeParser(TypeFactory.defaultInstance()).parse("com.example.DoesNotExist"));
                    }
                }
                """);
    }

    private static Case hikariCpUtilityElf() {
        return new Case(
                "HikariCP",
                "",
                "com/zaxxer/hikari/util/UtilityElf.java",
                "public static int getTransactionIsolation(final String transactionIsolationName)",
                "com.zaxxer.hikari.util.UtilityElf",
                """
                package com.zaxxer.hikari.util;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UnitMindMatrixInitialTest {
                    @Test void mapsReadCommitted() {
                        assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,
                                UtilityElf.getTransactionIsolation("TRANSACTION_READ_COMMITTED"));
                    }
                }
                """,
                """
                package com.zaxxer.hikari.util;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class UnitMindMatrixImprovedTest {
                    @Test void mapsReadCommitted() {
                        assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,
                                UtilityElf.getTransactionIsolation("TRANSACTION_READ_COMMITTED"));
                    }
                    @Test void mapsCaseInsensitiveNames() {
                        assertEquals(java.sql.Connection.TRANSACTION_READ_UNCOMMITTED,
                                UtilityElf.getTransactionIsolation("transaction_read_uncommitted"));
                    }
                    @Test void mapsRepeatableReadAndSerializable() {
                        assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                                UtilityElf.getTransactionIsolation("TRANSACTION_REPEATABLE_READ"));
                        assertEquals(java.sql.Connection.TRANSACTION_SERIALIZABLE,
                                UtilityElf.getTransactionIsolation("TRANSACTION_SERIALIZABLE"));
                    }
                    @Test void acceptsLegacyIntegerLevel() {
                        assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,
                                UtilityElf.getTransactionIsolation("2"));
                    }
                    @Test void rejectsUnknownNameButToleratesNull() {
                        assertThrows(IllegalArgumentException.class,
                                () -> UtilityElf.getTransactionIsolation("BOGUS"));
                        assertEquals(-1, UtilityElf.getTransactionIsolation(null));
                    }
                }
                """);
    }

    /** 一个矩阵用例：真实仓库 + 目标方法 + 初始/补测两份候选测试。 */
    static final class Case {
        final String repo;
        /** 多模块项目里的子模块目录；单模块项目为空串。 */
        final String moduleDir;
        final String sourcePath;
        final String methodDeclaration;
        final String targetClass;
        final String testPackage;
        final String initialTest;
        final String improvedTest;

        Case(String repo, String moduleDir, String sourcePath, String methodDeclaration, String targetClass,
             String initialTest, String improvedTest) {
            this.repo = repo;
            this.moduleDir = moduleDir == null ? "" : moduleDir;
            this.sourcePath = sourcePath;
            this.methodDeclaration = methodDeclaration;
            this.targetClass = targetClass;
            this.testPackage = targetClass.substring(0, targetClass.lastIndexOf('.'));
            this.initialTest = initialTest;
            this.improvedTest = improvedTest;
        }

        Path repoPath() {
            return Path.of("tmp/oss").toAbsolutePath().resolve(repo);
        }

        Path classesPath() {
            return repoPath().resolve(moduleDir).resolve("target/classes");
        }

        Path classpathFilePath() {
            return repoPath().resolve(moduleDir).resolve("target/unitmind.classpath");
        }

        Path sourcePathFile() {
            // 常规 Maven 布局是 src/main/java；guava 等项目直接用 src 作为源根，按存在性探测。
            Path standard = repoPath().resolve(moduleDir).resolve("src/main/java").resolve(sourcePath);
            if (Files.isRegularFile(standard)) {
                return standard;
            }
            return repoPath().resolve(moduleDir).resolve("src").resolve(sourcePath);
        }

        boolean isPrepared() {
            return Files.isDirectory(classesPath()) && Files.isRegularFile(classpathFilePath());
        }
    }
}
