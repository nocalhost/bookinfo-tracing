package dev.nocalhost.reviews;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jaegertracing.Configuration;
import io.jaegertracing.internal.JaegerTracer;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapExtractAdapter;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.apache.http.*;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;

@SpringBootApplication
@Controller
public class ReviewsApplication {

    private Gson GSON = new Gson();

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ReviewsApplication.class);
        app.setDefaultProperties(Collections
                .singletonMap("server.port", "9080"));
        app.run(args);
    }

    @Bean
    public static JaegerTracer getTracer() {
        Configuration.SamplerConfiguration samplerConfig = Configuration.SamplerConfiguration.fromEnv().withType("const").withParam(1);
        Configuration.ReporterConfiguration reporterConfig = Configuration.ReporterConfiguration.fromEnv().withLogSpans(true);
        Configuration config = new Configuration("reviews").withSampler(samplerConfig).withReporter(reporterConfig);
        return config.getTracer();
    }

    @Autowired
    private JaegerTracer tracer;

    private final static Boolean ratings_enabled = true;
    private final static String star_color = System.getenv("STAR_COLOR") == null ? "black" : System.getenv("STAR_COLOR");
    private final static String services_domain = System.getenv("SERVICES_DOMAIN") == null ? "" : ("." + System.getenv("SERVICES_DOMAIN"));
    private final static String ratings_hostname = System.getenv("RATINGS_HOSTNAME") == null ? "ratings" : System.getenv("RATINGS_HOSTNAME");
    private final static String ratings_service = "http://" + ratings_hostname + services_domain + ":9080/ratings";
    // HTTP headers to propagate for distributed tracing are documented at
    // https://istio.io/docs/tasks/telemetry/distributed-tracing/overview/#trace-context-propagation
    private final static String[] headers_to_propagate = {
            // All applications should propagate x-request-id. This header is
            // included in access log statements and is used for consistent trace
            // sampling and log sampling decisions in Istio.
            "x-request-id",

            // Lightstep tracing header. Propagate this if you use lightstep tracing
            // in Istio (see
            // https://istio.io/latest/docs/tasks/observability/distributed-tracing/lightstep/)
            // Note: this should probably be changed to use B3 or W3C TRACE_CONTEXT.
            // Lightstep recommends using B3 or TRACE_CONTEXT and most application
            // libraries from lightstep do not support x-ot-span-context.
            "x-ot-span-context",

            // Datadog tracing header. Propagate these headers if you use Datadog
            // tracing.
            "x-datadog-trace-id",
            "x-datadog-parent-id",
            "x-datadog-sampling-priority",

            // W3C Trace Context. Compatible with OpenCensusAgent and Stackdriver Istio
            // configurations.
            "traceparent",
            "tracestate",

            // Cloud trace context. Compatible with OpenCensusAgent and Stackdriver Istio
            // configurations.
            "x-cloud-trace-context",

            // Grpc binary trace context. Compatible with OpenCensusAgent nad
            // Stackdriver Istio configurations.
            "grpc-trace-bin",

            // b3 trace headers. Compatible with Zipkin, OpenCensusAgent, and
            // Stackdriver Istio configurations. Commented out since they are
            // propagated by the OpenTracing tracer above.
            "x-b3-traceid",
            "x-b3-spanid",
            "x-b3-parentspanid",
            "x-b3-sampled",
            "x-b3-flags",

            // Application-specific headers to forward.
            "end-user",
            "user-agent",
    };

    private String getJsonResponse(String productId, int starsReviewer1, int starsReviewer2) {
        String result = "{";
        result += "\"id\": \"" + productId + "\",";
        result += "\"reviews\": [";

        // reviewer 1:
        result += "{";
        result += "  \"reviewer\": \"Reviewer1\",";
        result += "  \"text\": \"An extremely entertaining play by Shakespeare. The slapstick humour is refreshing!\"";
        if (ratings_enabled) {
            if (starsReviewer1 != -1) {
                result += ", \"rating\": {\"stars\": " + starsReviewer1 + ", \"color\": \"" + star_color + "\"}";
            } else {
                result += ", \"rating\": {\"error\": \"Ratings service is currently unavailable\"}";
            }
        }
        result += "},";

        // reviewer 2:
        result += "{";
        result += "  \"reviewer\": \"Reviewer2\",";
        result += "  \"text\": \"Absolutely fun and entertaining. The play lacks thematic depth when compared to other plays by Shakespeare.\"";
        if (ratings_enabled) {
            if (starsReviewer2 != -1) {
                result += ", \"rating\": {\"stars\": " + starsReviewer2 + ", \"color\": \"" + star_color + "\"}";
            } else {
                result += ", \"rating\": {\"error\": \"Ratings service is currently unavailable\"}";
            }
        }
        result += "}";

        result += "]";
        result += "}";

        return result;
    }

    @RequestMapping(value = "/health", method = RequestMethod.GET)
    @ResponseBody
    public String health() {
        return "{\"status\": \"Reviews is healthy\"}";
    }

    private String getRatings(String productId, SpanContext spanContext) {

        HttpGet httpGet = new HttpGet(ratings_service + "/" + productId);
        CloseableHttpClient client = HttpClients.createDefault();

        // inject trace info to header
        tracer.inject(spanContext, Format.Builtin.TEXT_MAP, new RequestCarrier(httpGet));
        Header[] traceHeader = httpGet.getAllHeaders();
        System.out.printf("---getRatings---");
        for (Header header : traceHeader) {
            System.out.println(header);
        }

        String responseContent = null; // 响应内容
        CloseableHttpResponse response = null;
        try {
            response = client.execute(httpGet);
            HttpEntity entity = response.getEntity();// 响应体
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {// 正确返回
                responseContent = EntityUtils.toString(entity, "UTF-8");
            }
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (response != null)
                    response.close();
                if (client != null)
                    client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return responseContent;
    }

    @RequestMapping(value = "/reviews/{productId}", method = RequestMethod.GET)
    @ResponseBody
    public String bookReviewsById(@PathVariable("productId") int productId,
                                  HttpServletRequest request) {

        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String key = headerNames.nextElement();
            String value = request.getHeader(key);
            headers.put(key, value);
            System.out.println("" + key + ": " + value);
        }

        // extract trace info to context
        SpanContext spanContext = tracer.extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(headers));
        Span span = tracer.buildSpan("bookReviewsById").asChildOf(spanContext).start();

        int starsReviewer1 = -1;
        int starsReviewer2 = -1;

        if (ratings_enabled) {

            String ratings_string = this.getRatings(Integer.toString(productId), span.context());
            System.out.println(ratings_string);
            JsonObject ratings = JsonParser.parseString(ratings_string).getAsJsonObject().getAsJsonObject("ratings");
            if (!ratings.isJsonNull()) {
                starsReviewer1 = ratings.getAsJsonPrimitive("Reviewer1").getAsInt();
                starsReviewer2 = ratings.getAsJsonPrimitive("Reviewer2").getAsInt();
            }
        }
        span.finish();
        return getJsonResponse(Integer.toString(productId), starsReviewer1, starsReviewer2);
    }

}
