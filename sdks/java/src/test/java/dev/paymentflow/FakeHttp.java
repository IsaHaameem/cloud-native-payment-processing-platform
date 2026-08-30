package dev.paymentflow;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * A stub {@link HttpClient} that answers from a script of canned responses and records every
 * request it was asked to send. {@code java.net.http.HttpClient} is an abstract class with a
 * {@code protected} constructor, so this is a plain subclass — no framework needed.
 */
final class FakeHttp extends HttpClient {

    /** One scripted turn: either a response, or a throwable to raise. */
    record Turn(int status, String body, Map<String, String> headers, IOException raise) {

        static Turn ok(int status, String body) {
            return new Turn(status, body, Map.of(), null);
        }

        static Turn ok(int status, String body, Map<String, String> headers) {
            return new Turn(status, body, headers, null);
        }

        static Turn ioError(IOException e) {
            return new Turn(0, null, Map.of(), e);
        }
    }

    private final List<Turn> script;
    private int index;
    final List<HttpRequest> requests = new ArrayList<>();

    FakeHttp(Turn... turns) {
        this.script = new ArrayList<>(List.of(turns));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        requests.add(request);
        Turn turn = script.get(Math.min(index, script.size() - 1));
        index++;
        if (turn.raise() != null) {
            throw turn.raise();
        }
        return (HttpResponse<T>) new StubResponse(request, turn);
    }

    int calls() {
        return index;
    }

    // ── everything below is inert plumbing the abstract class demands ──────────────────────

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    private static final class StubResponse implements HttpResponse<String> {

        private final HttpRequest request;
        private final Turn turn;

        StubResponse(HttpRequest request, Turn turn) {
            this.request = request;
            this.turn = turn;
        }

        @Override
        public int statusCode() {
            return turn.status();
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            Map<String, List<String>> map = new java.util.HashMap<>();
            turn.headers().forEach((k, v) -> map.put(k, List.of(v)));
            return HttpHeaders.of(map, (a, b) -> true);
        }

        @Override
        public String body() {
            return turn.body();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    /** A convenience for the tests that build a client around this fake. */
    static PaymentFlow client(FakeHttp http) {
        return PaymentFlow.builder().apiKey("sk_test_fake").httpClient(http).maxRetries(3).build();
    }

    static Function<String, String> header(HttpRequest request) {
        return name -> request.headers().firstValue(name).orElse(null);
    }
}
