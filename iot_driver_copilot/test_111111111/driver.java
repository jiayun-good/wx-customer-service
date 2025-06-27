package shifu.driver;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class DeviceShifuDriver {

    // Environment Variable Names
    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";

    // Simulated Device Data (Replace with actual device integration logic)
    private static final List<Map<String, Object>> SIMULATED_DATA_POINTS = new ArrayList<>();

    static {
        // For demonstration, add some points
        for (int i = 1; i <= 50; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("timestamp", System.currentTimeMillis() - i * 1000);
            point.put("value", Math.random() * 100);
            SIMULATED_DATA_POINTS.add(point);
        }
    }

    public static void main(String[] args) throws Exception {
        String host = System.getenv(ENV_SERVER_HOST);
        String portStr = System.getenv(ENV_SERVER_PORT);

        if (host == null) host = "0.0.0.0";
        int port = 8080;
        if (portStr != null) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/points", DeviceShifuDriver::handlePoints);
        server.createContext("/commands", DeviceShifuDriver::handleCommands);

        server.setExecutor(null);
        server.start();
        System.out.printf("DeviceShifu Driver started at http://%s:%d/%n", host, port);
    }

    // Handler for /points (GET)
    private static void handlePoints(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
        int page = params.containsKey("page") ? parseIntOrDefault(params.get("page"), 1) : 1;
        int limit = params.containsKey("limit") ? parseIntOrDefault(params.get("limit"), 10) : 10;
        if (limit <= 0) limit = 10;
        if (page <= 0) page = 1;

        int fromIndex = (page - 1) * limit;
        int toIndex = Math.min(fromIndex + limit, SIMULATED_DATA_POINTS.size());
        List<Map<String, Object>> results = SIMULATED_DATA_POINTS.subList(
                Math.min(fromIndex, SIMULATED_DATA_POINTS.size()), toIndex
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("page", page);
        response.put("limit", limit);
        response.put("total", SIMULATED_DATA_POINTS.size());
        response.put("data", results);

        String json = mapToJson(response);

        sendResponse(exchange, 200, json, "application/json");
    }

    // Handler for /commands (POST)
    private static void handleCommands(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        String contentType = getHeaderIgnoreCase(exchange.getRequestHeaders(), "Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            sendResponse(exchange, 400, "{\"error\":\"Content-Type must be application/json\"}", "application/json");
            return;
        }

        String body = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));

        // Simulate processing command payload
        // In real implementation, parse JSON and send commands to the device
        String result = "{\"status\":\"success\",\"received\":" + escapeJson(body) + "}";

        sendResponse(exchange, 200, result, "application/json");
    }

    // Utility: Parse Query String
    private static Map<String, String> queryToMap(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] params = query.split("&");
        for (String param : params) {
            int idx = param.indexOf('=');
            if (idx > 0) {
                String key = param.substring(0, idx);
                String val = param.substring(idx + 1);
                map.put(key, val);
            }
        }
        return map;
    }

    // Utility: Parse integer with default
    private static int parseIntOrDefault(String s, int defaultVal) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    // Utility: Send HTTP Response
    private static void sendResponse(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    // Utility: Basic Map to JSON (for simple data)
    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            sb.append(toJsonValue(e.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String toJsonValue(Object o) {
        if (o instanceof String) return "\"" + escapeJson((String) o) + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object v : (List<?>) o) {
                if (!first) sb.append(",");
                sb.append(toJsonValue(v));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        if (o instanceof Map) {
            //noinspection unchecked
            return mapToJson((Map<String, Object>) o);
        }
        return "null";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Utility: Get header ignoring case
    private static String getHeaderIgnoreCase(Headers headers, String key) {
        for (String h : headers.keySet()) {
            if (h.equalsIgnoreCase(key)) {
                return headers.getFirst(h);
            }
        }
        return null;
    }
}