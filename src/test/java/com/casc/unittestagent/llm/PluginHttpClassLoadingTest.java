package com.casc.unittestagent.llm;

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.cl.PluginClassLoader;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.util.lang.UrlClassLoader;
import com.sun.net.httpserver.HttpServer;
import junit.framework.TestCase;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Uses the installed plugin's JARs and IDEA's real child-first loader, rather than Gradle's flat classpath. */
public class PluginHttpClassLoadingTest extends TestCase {
    private PluginClassLoader loader;
    private HttpServer server;
    private final CountDownLatch releaseServer = new CountDownLatch(1);

    @Override
    protected void setUp() throws Exception {
        Path lib = Path.of(System.getProperty("unitmind.test.pluginLibDirectory"));
        assertTrue("Prepared plugin libraries must exist: " + lib, Files.isDirectory(lib));
        List<Path> jars;
        try (var paths = Files.list(lib)) {
            jars = paths.filter(path -> path.toString().endsWith(".jar")).sorted().toList();
        }
        assertFalse(jars.isEmpty());
        PluginDescriptor descriptor = mock(PluginDescriptor.class);
        when(descriptor.getPluginId()).thenReturn(PluginId.getId("com.casc.unittestagent"));
        when(descriptor.getName()).thenReturn("UnitMind");
        when(descriptor.getPluginPath()).thenReturn(lib.getParent());
        loader = new PluginClassLoader(jars, UrlClassLoader.build().files(jars).get().getClassPath(),
                new IdeaPluginDescriptorImpl[0], descriptor, getClass().getClassLoader(), null, null, List.of());
        assertSame(loader, loader.loadClass(OpenAiCompatibleLlmClient.class.getName()).getClassLoader());
        assertSame(loader, loader.loadClass("okhttp3.OkHttpClient").getClassLoader());
    }

    @Override
    protected void tearDown() {
        releaseServer.countDown();
        if (server != null) server.stop(0);
    }

    public void testKotlinRuntimeAndSuppressedExceptionsComeFromIdea() throws Exception {
        IOException primary = new IOException("gateway failed");
        IOException previous = new IOException("previous route failed");
        // Exact OkHttp failure path in the intranet screenshot; must preserve the original IO error.
        Object result = loader.loadClass("okhttp3.internal.Util")
                .getMethod("withSuppressed", Exception.class, List.class)
                .invoke(null, primary, List.of(previous));
        assertSame(primary, result);
        assertEquals(1, primary.getSuppressed().length);
        assertSame(previous, primary.getSuppressed()[0]);
        for (String name : List.of("kotlin.ExceptionsKt", "kotlin.internal.PlatformImplementations",
                "kotlin.internal.jdk8.JDK8PlatformImplementations")) {
            assertSame("Kotlin must delegate to IDEA: " + name,
                    getClass().getClassLoader().loadClass(name), loader.loadClass(name));
        }
    }

    public void testInstalledPluginCoverageParserReadsJava21AndJava25Bytecode() throws Exception {
        Class<?> reader = loader.loadClass("org.objectweb.asm.ClassReader");
        assertSame("Coverage must use the plugin's bundled parser", loader, reader.getClassLoader());
        Class<?> analyzer = loader.loadClass("org.jacoco.core.analysis.Analyzer");
        Class<?> store = loader.loadClass("org.jacoco.core.data.ExecutionDataStore");
        Class<?> builder = loader.loadClass("org.jacoco.core.analysis.CoverageBuilder");
        Class<?> visitor = loader.loadClass("org.jacoco.core.analysis.ICoverageVisitor");
        byte[] original;
        try (var input = getClass().getResourceAsStream("PluginHttpClassLoadingTest.class")) {
            assertNotNull(input);
            original = input.readAllBytes();
        }
        for (int major : new int[]{65, 69}) {
            byte[] bytes = original.clone();
            bytes[6] = (byte) (major >>> 8); bytes[7] = (byte) major;
            Object coverage = builder.getConstructor().newInstance();
            Object parser = analyzer.getConstructor(store, visitor)
                    .newInstance(store.getConstructor().newInstance(), coverage);
            analyzer.getMethod("analyzeClass", byte[].class, String.class).invoke(parser, bytes, "Java " + (major - 44));
            assertEquals(1, ((java.util.Collection<?>) builder.getMethod("getClasses").invoke(coverage)).size());
        }
    }

    public void testConnectivityUsesPostAuthorizationAndJson() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> request = new AtomicReference<>();
        startServer(200, chatResponse("ok"), () -> {}, method, authorization, contentType, request);
        assertEquals("连接成功：ok", connect(newClient(endpoint(), 5)));
        assertEquals("POST", method.get());
        assertEquals("Bearer local-fixture-key", authorization.get());
        assertTrue(contentType.get().startsWith("application/json"));
        assertEquals("local-fixture-model", new JSONObject(request.get()).getString("model"));
    }

    public void testGenerationUsesPluginLoadedHttpClient() throws Exception {
        String code = "class LocalFixtureTest {}";
        String payload = new JSONObject().put("testCode", code).put("mockStrategies", List.of())
                .put("warnings", List.of()).toString();
        startServer(200, chatResponse(payload));
        Class<?> requestClass = loader.loadClass(LlmGenerationRequest.class.getName());
        Object request = requestClass.getConstructor().newInstance();
        requestClass.getMethod("setClassName", String.class).invoke(request, "LocalFixture");
        requestClass.getMethod("setMethodName", String.class).invoke(request, "value");
        requestClass.getMethod("setMethodSourceCode", String.class).invoke(request, "int value() { return 1; }");
        Object client = newClient(endpoint(), 5);
        Object response = client.getClass().getMethod("generateUnitTest", requestClass).invoke(client, request);
        assertEquals(code, response.getClass().getMethod("getTestCode").invoke(response));
    }

    public void testInstalledPluginUsesSseForBothProbeAndGeneration() throws Exception {
        String code = "class LocalStreamTest {}";
        String payload = new JSONObject().put("testCode", code).put("mockStrategies", List.of())
                .put("warnings", List.of()).toString();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                var request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                assertTrue(request.getBoolean("stream"));
                String finish = requests.incrementAndGet() == 1 ? "length" : "stop";
                String data = "data: " + new JSONObject().put("choices", List.of(new JSONObject()
                        .put("delta", new JSONObject().put("content", payload)).put("finish_reason", finish))) + "\n\n";
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        Object client = newClient(endpoint(), 5);
        assertEquals("连接成功：" + payload, connect(client));
        Class<?> requestClass = loader.loadClass(LlmGenerationRequest.class.getName());
        Object response = client.getClass().getMethod("generateUnitTest", requestClass)
                .invoke(client, requestClass.getConstructor().newInstance());
        assertEquals(code, response.getClass().getMethod("getTestCode").invoke(response));
        assertEquals(2, requests.get());
    }

    public void testHttpErrorRetainsGatewayStatusAndBody() throws Exception {
        startServer(503, "local gateway unavailable");
        IOException failure = connectionFailure(newClient(endpoint(), 5));
        assertTrue(failure.getMessage(), failure.getMessage().contains("503"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("local gateway unavailable"));
    }

    public void testReadTimeoutRemainsAnIoException() throws Exception {
        startServer(200, chatResponse("ok"), () -> {
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        IOException failure = connectionFailure(newClient(endpoint(), 1));
        assertTrue(failure.toString(), failure instanceof SocketTimeoutException);
    }

    public void testRefusedConnectionRemainsAnIoException() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = unused.getLocalPort();
        }
        IOException failure = connectionFailure(newClient("http://127.0.0.1:" + port + "/v1/chat/completions", 1));
        assertNotNull(failure.getMessage());
    }

    private Object newClient(String endpoint, int timeout) throws Exception {
        Class<?> configClass = loader.loadClass(LlmConfig.class.getName());
        Object config = configClass.getConstructor().newInstance();
        configClass.getMethod("setApiUrl", String.class).invoke(config, endpoint);
        configClass.getMethod("setApiKey", String.class).invoke(config, "local-fixture-key");
        configClass.getMethod("setModel", String.class).invoke(config, "local-fixture-model");
        configClass.getMethod("setTimeoutSeconds", int.class).invoke(config, timeout);
        return loader.loadClass(OpenAiCompatibleLlmClient.class.getName()).getConstructor(configClass).newInstance(config);
    }

    private String connect(Object client) throws Exception {
        return (String) client.getClass().getMethod("testConnection").invoke(client);
    }

    private IOException connectionFailure(Object client) throws Exception {
        try {
            connect(client);
            fail("Expected the original HTTP/IO failure");
            return null;
        } catch (InvocationTargetException invocation) {
            assertTrue("Must not mask IO errors with class loading failures: " + invocation.getCause(),
                    invocation.getCause() instanceof IOException);
            return (IOException) invocation.getCause();
        }
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private String chatResponse(String content) {
        return new JSONObject().put("choices", List.of(new JSONObject().put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", content)))).toString();
    }

    private void startServer(int status, String response) throws IOException {
        startServer(status, response, () -> {}, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
    }

    private void startServer(int status, String response, Runnable beforeResponse,
                             AtomicReference<String> method, AtomicReference<String> authorization,
                             AtomicReference<String> contentType, AtomicReference<String> request) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                method.set(exchange.getRequestMethod());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                beforeResponse.run();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }
}
