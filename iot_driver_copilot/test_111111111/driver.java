import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DeviceShifuDriver {

    // Simulated device data storage
    private static final List<Map<String, Object>> DATA_POINTS = new ArrayList<>();
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_HOST = "0.0.0.0";

    static {
        // Populate with some dummy telemetry data for demonstration
        for (int i = 1; i <= 50; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("timestamp", System.currentTimeMillis() - (50 - i) * 1000);
            point.put("value", Math.random() * 100);
            // Add any additional fields as needed
            DATA_POINTS.add(point);
        }
    }

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("DRIVER_HOST", DEFAULT_HOST);
        int port = getIntEnv("DRIVER_PORT", DEFAULT_PORT);

        InetSocketAddress addr = new InetSocketAddress(host, port);
        HttpServer server = HttpServer.create(addr, 0);

        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(null);
        server.start();
        System.out.printf("DeviceShifuDriver HTTP server started at http://%s:%d%n", host, port);
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
            int page = parsePositiveInt(queryParams.getOrDefault("page", "1"), 1);
            int limit = parsePositiveInt(queryParams.getOrDefault("limit", "10"), 10);

            // Simple pagination logic
            int fromIndex = (page - 1) * limit;
            int toIndex = Math.min(fromIndex + limit, DATA_POINTS.size());

            List<Map<String, Object>> pagedData = new ArrayList<>();
            if (fromIndex < DATA_POINTS.size() && fromIndex >= 0) {
                pagedData = DATA_POINTS.subList(fromIndex, toIndex);
            }

            Map<String, Object> responseObj = new HashMap<>();
            responseObj.put("page", page);
            responseObj.put("limit", limit);
            responseObj.put("total", DATA_POINTS.size());
            responseObj.put("data", pagedData);

            String json = toJson(responseObj);
            sendResponse(exchange, 200, json, "application/json");
        }
    }

    // Handler for POST /commands
    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
                sendResponse(exchange, 400, "{\"error\":\"Content-Type must be application/json\"}", "application/json");
                return;
            }

            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // For demo, just echo the command back
            Map<String, Object> response = new HashMap<>();
            response.put("status", "received");
            response.put("received_command", requestBody);

            sendResponse(exchange, 200, toJson(response), "application/json");
        }
    }

    // Utility: parse query string
    private static Map<String, String> parseQueryParams(String query) throws UnsupportedEncodingException {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    // Utility: send HTTP response
    private static void sendResponse(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Utility: parse int with default
    private static int parsePositiveInt(String s, int def) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    // Utility: parse integer env
    private static int getIntEnv(String key, int def) {
        String v = System.getenv(key);
        if (v != null) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    // Utility: Convert Map/List to JSON (minimal, no external libs)
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + escape((String) obj) + "\"";
        if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Object o : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                sb.append(toJson(e.getKey().toString())).append(":").append(toJson(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (obj instanceof Iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (Iterable<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(o));
                first = false;
            }
            return sb.append("]").toString();
        }
        return "\"" + escape(obj.toString()) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}