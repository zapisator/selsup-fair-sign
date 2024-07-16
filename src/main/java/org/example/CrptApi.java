package org.example;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Singular;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.jctools.queues.MpscUnboundedArrayQueue;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class CrptApi implements AutoCloseable {

    public interface InboundCollector {
        void offer(Document document, String signature);

        Pair<Document, String> poll();
    }

    public interface Serializer {
        String toJson(Document document);
    }

    public interface HttpClient {
        void post(String url, String json, String signature);
    }

    public interface OutboundExecutor {
        void start();

        void stop();
    }

    public interface OutboundConfiguration {
        TimeUnit timeUnit();

        int requestLimit();

        Serializer serializer();

        String url();

        HttpClient httpClient();
    }

    private final InboundCollector collector;
    private final OutboundExecutor executor;

    public static CrptApi create() {
        return create(DefaultOutboundConfiguration.builder().build());
    }

    public static CrptApi create(OutboundConfiguration configuration) {
        requireNonNull(configuration);
        log.info("Creating CrptApi instance with configuration: {}", configuration);
        final DefaultInboundCollector collector = new DefaultInboundCollector();
        final DefaultOutboundExecutor executor
                = new DefaultOutboundExecutor(configuration, collector);
        return new CrptApi(collector, executor).start();
    }

    public static void main(String[] args) {
        try (final CrptApi apiCaller = CrptApi.create(CrptApi.DefaultOutboundConfiguration.builder()
                .url("https://reqres.in/api/users")
                .build())) {
            final ScheduledExecutorService simpleExecutorServices = Executors
                    .newSingleThreadScheduledExecutor();
            final ScheduledExecutorService excessiveExecutorServices = Executors
                    .newSingleThreadScheduledExecutor();

            simpleExecutorServices.scheduleWithFixedDelay(() -> apiCaller.createDocument(
                            FakeDataGenerator.createFakeDocument(),
                            ""
                    ),
                    0, 1, SECONDS
            );
            simpleExecutorServices.awaitTermination(5, TimeUnit.SECONDS);
            simpleExecutorServices.shutdown();

            excessiveExecutorServices.scheduleWithFixedDelay(() -> apiCaller.createDocument(
                            FakeDataGenerator.createFakeDocument(),
                            ""
                    ),
                    6, 1, MILLISECONDS
            );
            excessiveExecutorServices.awaitTermination(2, TimeUnit.SECONDS);
            excessiveExecutorServices.shutdown();
        } catch (InterruptedException e) { // Compliant; the interrupted state is restored
            log.warn("Interrupted!", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("CrptApi exception: {}", e.getMessage());
            throw new CrptApiException(e);
        }
    }

    @Override
    public void close() throws Exception {
        log.info("Closing CrptApi instance");
        executor.stop();
    }

    public void createDocument(Document document, String signature) {
        log.info(
                "Sending request to CrptApi: {}\nSignature: {}",
                document.toString(), signature
        );
        collector.offer(document, signature);
    }

    private CrptApi start() {
        log.info("Starting CrptApi instance");
        executor.start();
        return this;
    }

    @Builder
    @Value
    @Accessors(fluent = true)
    public static class DefaultOutboundConfiguration implements OutboundConfiguration {
        @Builder.Default
        @NonNull
        TimeUnit timeUnit = TimeUnit.SECONDS;
        @Builder.Default
        int requestLimit = 100;
        @Builder.Default
        @NonNull
        Serializer serializer = new DefaultSerializer();
        @Builder.Default
        @NonNull
        String url = "https://ismp.crpt.ru/api/v3/lk/documents/create";
        @Builder.Default
        @NonNull
        HttpClient httpClient = new DefaultHttpClient();
    }

    public static class DefaultInboundCollector implements InboundCollector {
        private static final int CHUNK_SIZE = 128;
        private final MpscUnboundedArrayQueue<Pair<Document, String>> inboundQueue
                = new MpscUnboundedArrayQueue<>(CHUNK_SIZE);

        @Override
        public void offer(Document document, String signature) {
            log.debug("Offering document to request queue: {}", document);
            inboundQueue.offer(Pair.of(document, signature));
        }

        @Override
        public Pair<Document, String> poll() {
            log.debug("Polling document from request queue");
            return inboundQueue.poll();
        }

    }

    private static class DefaultSerializer implements Serializer {
        private final ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        @Override
        public String toJson(Document document) {
            log.debug("Serializing document to JSON: {}", document);
            try {
                return objectMapper.writeValueAsString(document);
            } catch (JsonProcessingException e) {
                log.error("Serialization error: {}", e.getMessage());
                throw new SerializationException(e);
            }
        }

        @Override
        public String toString() {
            return "DefaultSerializer{" +
                    "objectMapper=" + objectMapper.getClass().getCanonicalName() +
                    '}';
        }
    }

    @RequiredArgsConstructor
    public static class DefaultHttpClient implements HttpClient {
        private final OkHttpClient client = new OkHttpClient();

        @Override
        public void post(String url, String json, String signature) {
            log.debug("Sending POST request to CrptApi: {}", url);
            final RequestBody body = RequestBody.create(json, MediaType.parse(APPLICATION_JSON));
            final Request apiRequest = new Request.Builder()
                    .url(url)
                    .addHeader("Signature", signature)
                    .post(body)
                    .build();
            try (final Response response = client.newCall(apiRequest).execute()) {
                log.info(
                        "Response code: {}\nResponse body: {}",
                        response.code(), response.body() == null
                                ? "no body"
                                : response.body().string()
                );
                log.debug("Response headers: {}", response.headers());
            } catch (IOException e) {
                log.error("Http client exception: {}", e.getMessage());
                throw new HttpClientException(e);
            }
        }

        @Override
        public String toString() {
            return "DefaultHttpClient{" +
                    "client=" + client.getClass().getCanonicalName() +
                    '}';
        }
    }

    @RequiredArgsConstructor
    public static class DefaultOutboundExecutor implements OutboundExecutor {

        private final OutboundConfiguration configuration;
        private final InboundCollector collector;
        private final ScheduledExecutorService executor = Executors
                .newSingleThreadScheduledExecutor();

        @Override
        public void start() {
            final long delay = 0;
            final long period = toPeriod();
            log.info("Starting request execution. Setting request execution period: {} MILLISECONDS", period);

            executor.scheduleWithFixedDelay(() -> {
                        log.debug("Checking request queue for pending documents");
                        final Pair<Document, String> pair = collector.poll();
                        log.debug("Poll result is null: {}", pair == null);
                        if (pair != null) {
                            log.debug("Document found in queue, processing...");
                            final String json = configuration.serializer().toJson(pair.getLeft());
                            log.debug("Json to send: {}", json);
                            configuration.httpClient().post(
                                    configuration.url(), json, pair.getRight()
                            );
                        }
                    },
                    delay, period, MILLISECONDS
            );
        }

        private long toPeriod() {
            final long period = MILLISECONDS.convert(1, configuration.timeUnit())
                    / configuration.requestLimit();
            log.debug(
                    "Calculating request execution period: timeUnit = {}, requestLimit = {}",
                    configuration.timeUnit(),
                    configuration.requestLimit()
            );
            if (period == 0) {
                throw new OutboundRequestExecutorException(
                        "Requests cannot be made more frequently than 1 per MILLISECOND: "
                                + "request limit='"
                                + configuration.requestLimit()
                                + "', time unit='"
                                + configuration.requestLimit()
                                + "'"
                );
            }
            return period;
        }

        @Override
        public void stop() {
            log.info("Stopping request execution");
            executor.shutdown();
        }
    }

    public static class SerializationException extends RuntimeException {
        public SerializationException(Exception e) {
            super(e);
        }
    }

    public static class HttpClientException extends RuntimeException {
        public HttpClientException(Exception e) {
            super(e);
        }
    }

    public static class CrptApiException extends RuntimeException {
        public CrptApiException(Exception e) {
            super(e);
        }
    }

    public static class OutboundRequestExecutorException extends RuntimeException {
        public OutboundRequestExecutorException(String message) {
            super(message);
        }
    }

    @Value
    @Builder
    public static class Document {
        @JsonProperty("doc_id")
        String docId;
        @JsonProperty("doc_status")
        String docStatus;
        @JsonProperty("doc_type")
        String docType;
        @JsonProperty("importRequest")
        boolean importRequest;
        @JsonProperty("owner_inn")
        String ownerInn;
        @JsonProperty("participant_inn")
        String participantInn;
        @JsonProperty("producer_inn")
        String producerInn;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @JsonProperty("production_date")
        LocalDate productionDate;
        @JsonProperty("production_type")
        String productionType;
        @JsonProperty("products")
        @Singular
        List<Product> products;
        @JsonProperty("reg_date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate regDate;
        @JsonProperty("reg_number")
        String regNumber;
    }

    @Value
    @Builder
    public static class Product {
        @JsonProperty("certificate_document")
        String certificateDocument;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @JsonProperty("certificate_document_date")
        LocalDate certificateDocumentDate;
        @JsonProperty("certificate_document_number")
        String certificateDocumentNumber;
        @JsonProperty("owner_inn")
        String ownerInn;
        @JsonProperty("producer_inn")
        String producerInn;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @JsonProperty("production_date")
        LocalDate productionDate;
        @JsonProperty("tnved_code")
        String tnvedCode;
        @JsonProperty("uit_code")
        String uitCode;
        @JsonProperty("uitu_code")
        String uituCode;
    }

    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class FakeDataGenerator {

        public static CrptApi.Document createFakeDocument() {
            return CrptApi.Document.builder()
                    .docId(UUID.randomUUID().toString())
                    .docStatus("DRAFT")
                    .docType("LP_INTRODUCE_GOODS")
                    .importRequest(false)
                    .ownerInn("1234567890")
                    .participantInn("9876543210")
                    .producerInn("5678901234")
                    .productionDate(LocalDate.now().minusDays(10))
                    .productionType("OWN_PRODUCTION")
                    .products(createFakeProducts(3))
                    .regDate(LocalDate.now())
                    .regNumber("12345678901234567890")
                    .build();
        }

        public static List<CrptApi.Product> createFakeProducts(int count) {
            List<CrptApi.Product> products = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                products.add(createFakeProduct());
            }
            return products;
        }

        public static CrptApi.Product createFakeProduct() {
            return CrptApi.Product.builder()
                    .certificateDocument("CERTIFICATE")
                    .certificateDocumentDate(LocalDate.now().minusMonths(2))
                    .certificateDocumentNumber(UUID.randomUUID().toString())
                    .ownerInn("9876543210")
                    .producerInn("5678901234")
                    .productionDate(LocalDate.now().minusDays(5))
                    .tnvedCode("12345678")
                    .uitCode(UUID.randomUUID().toString())
                    .uituCode(UUID.randomUUID().toString())
                    .build();
        }
    }
}