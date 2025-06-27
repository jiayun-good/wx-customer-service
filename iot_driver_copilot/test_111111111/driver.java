import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class DeviceShifuDriver {

    // Simulated device data points (in-memory for demonstration)
    private static final List<Map<String, Object>> DATA_POINTS = Collections.synchronizedList(new ArrayList<>());
    private static final int DEFAULT_PAGE_SIZE = 10;

    // Device configuration loaded from environment
    private static final String DEVICE_IP = System.getenv("DEVICE_IP");
    private static final String DEVICE_PORT = System.getenv("DEVICE_PORT");
    private static final String SERVER_HOST = System.getenv("SERVER_HOST") != null ? System.getenv("SERVER_HOST") : "0.0.0.0";
    private static final int SERVER_PORT = System.getenv("SERVER_PORT") != null ? Integer.parseInt(System.getenv("SERVER_PORT")) : 8080;

    public static void main(String[] args) throws Exception {
        initSampleData();

        HttpServer server = HttpServer.create(new InetSocketAddress(SERVER_HOST, SERVER_PORT), 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(Executors.newCachedThreadPool());
        System.out.println("DeviceShifuDriver HTTP Server started on " + SERVER_HOST + ":" + SERVER_PORT);
        server.start();
    }

    // Populate with sample telemetry data
    private static void initSampleData() {
        for (int i = 1; i <= 50; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("timestamp", System.currentTimeMillis() - i * 10000L);
            point.put("temperature", 20.0 + Math.random() * 5);
            point.put("humidity", 30.0 + Math.random() * 10);
            DATA_POINTS.add(point);
        }
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());

            int page = 1;
            int limit = DEFAULT_PAGE_SIZE;
            try {
                if (params.containsKey("page")) {
                    page = Integer.parseInt(params.get("page"));
                    if (page < 1) page = 1;
                }
                if (params.containsKey("limit")) {
                    limit = Integer.parseInt(params.get("limit"));
                    if (limit < 1) limit = DEFAULT_PAGE_SIZE;
                }
            } catch (NumberFormatException ignored) {}

            int fromIndex = (page - 1) * limit;
            int toIndex = Math.min(fromIndex + limit, DATA_POINTS.size());
            List<Map<String, Object>> pageData = new ArrayList<>();
            if (fromIndex < DATA_POINTS.size())
                pageData = DATA_POINTS.subList(fromIndex, toIndex);

            Map<String, Object> result = new HashMap<>();
            result.put("page", page);
            result.put("limit", limit);
            result.put("total", DATA_POINTS.size());
            result.put("data", pageData);

            sendJsonResponse(exchange, 200, toJson(result));
        }
    }

    // Handler for POST /commands
    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            String contentType = Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type")).orElse("");
            if (!contentType.toLowerCase().contains("application/json")) {
                sendJsonResponse(exchange, 415, "{\"error\":\"Content-Type must be application/json\"}");
                return;
            }

            String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            // In a real driver, parse the command & interact with device.
            // Here, just echo the command and respond with status.
            Map<String, Object> response = new HashMap<>();
            response.put("received_command", body);
            response.put("status", "success");
            sendJsonResponse(exchange, 200, toJson(response));
        }
    }

    // --- Utility methods ---

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.trim().isEmpty()) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=", 2);
            if (entry.length > 0) {
                String key = urlDecode(entry[0]);
                String value = entry.length > 1 ? urlDecode(entry[1]) : "";
                result.put(key, value);
            }
        }
        return result;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, json.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    // Simple JSON serializer for maps and lists
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey().toString())).append("\":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(o));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String) {
            return "\"" + escapeJson((String) obj) + "\"";
        } else if (obj == null) {
            return "null";
        } else {
            return obj.toString();
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}