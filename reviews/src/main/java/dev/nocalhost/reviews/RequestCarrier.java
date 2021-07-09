package dev.nocalhost.reviews;

import io.opentracing.propagation.TextMap;
import org.apache.http.client.methods.HttpUriRequest;

import java.util.Iterator;
import java.util.Map;

public class RequestCarrier implements TextMap {
    private final HttpUriRequest request;

    public RequestCarrier(HttpUriRequest request) {
        this.request = request;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        throw new UnsupportedOperationException("carrier is writer-only");
    }

    @Override
    public void put(String key, String value) {
        request.addHeader(key, value);
    }
}
