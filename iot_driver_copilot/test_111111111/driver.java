import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class DeviceShifuDriver {

    // Environment variable keys
    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP"; // If device IP is needed for real device connection

    // Simulated data for demonstration
    private static final List<Map<String, Object>> DUMMY_DATA_POINTS = Arrays.asList(
            createDataPoint(1, "temperature", 25.2, System.currentTimeMillis()),
            createDataPoint(2, "humidity", 40.5, System.currentTimeMillis()),
            createDataPoint(3, "pressure", 101.2, System.currentTimeMillis())
    );

    private static Map<String, Object> createDataPoint(int id, String key, Object value, long timestamp) {
        Map<String, Object> point = new HashMap<>();
        point.put("id", id);
        point.put("key", key);
        point.put("value", value);
        point.put("timestamp", timestamp);
        return point;
    }

    public static void main(String[] args) throws Exception {
        String host = System.getenv(ENV_SERVER_HOST);
        String portStr = System.getenv(ENV_SERVER_PORT);
        String deviceIp = System.getenv(ENV_DEVICE_IP);

        if (host == null || portStr == null) {
            System.err.println("Environment variables SERVER_HOST and SERVER_PORT must be set.");
            System.exit(1);
        }

        int port = Integer.parseInt(portStr);

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("DeviceShifu driver started at http://" + host + ":" + port);
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> queryParams = queryToMap(exchange.getRequestURI().getRawQuery());

            int page = 1;
            int limit = DUMMY_DATA_POINTS.size();

            if (queryParams.containsKey("page")) {
                try {
                    page = Integer.parseInt(queryParams.get("page"));
                } catch (NumberFormatException ignored) {}
            }
            if (queryParams.containsKey("limit")) {
                try {
                    limit = Integer.parseInt(queryParams.get("limit"));
                } catch (NumberFormatException ignored) {}
            }

            // Filtering
            List<Map<String, Object>> filtered = new ArrayList<>(DUMMY_DATA_POINTS);
            for (String key : queryParams.keySet()) {
                if (!key.equals("page") && !key.equals("limit")) {
                    filtered.removeIf(dp -> !String.valueOf(dp.get("key")).equalsIgnoreCase(queryParams.get(key)));
                }
            }

            // Pagination
            int fromIndex = Math.max(0, (page - 1) * limit);
            int toIndex = Math.min(filtered.size(), fromIndex + limit);

            List<Map<String, Object>> pageData = fromIndex < toIndex ? filtered.subList(fromIndex, toIndex) : Collections.emptyList();

            Map<String, Object> response = new HashMap<>();
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", filtered.size());
            response.put("data", pageData);

            String json = toJson(response);
            sendJsonResponse(exchange, 200, json);
        }
    }

    // Handler for POST /commands
    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> commandPayload = parseJson(body);

            // Simulate device command execution
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("received", commandPayload);
            result.put("timestamp", System.currentTimeMillis());

            String response = toJson(result);
            sendJsonResponse(exchange, 200, response);
        }
    }

    // Utility: Parse query string into map
    private static Map<String, String> queryToMap(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        String[] params = query.split("&");
        for (String param : params) {
            String[] pair = param.split("=");
            if (pair.length == 2) {
                map.put(pair[0], pair[1]);
            }
        }
        return map;
    }

    // Utility: Send text response
    private static void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] resp = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    // Utility: Send JSON response
    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] resp = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    // Utility: Minimal JSON serialization (for demo, not production-ready)
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            sb.append(toJsonValue(e.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String toJson(List<?> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(",");
            sb.append(toJsonValue(o));
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private static String toJsonValue(Object value) {
        if (value instanceof Map) {
            return toJson((Map<String, Object>) value);
        } else if (value instanceof List) {
            return toJson((List<?>) value);
        } else if (value instanceof String) {
            return "\"" + value.toString().replace("\"", "\\\"") + "\"";
        } else if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        } else if (value == null) {
            return "null";
        }
        return "\"" + value.toString() + "\"";
    }

    // Utility: Minimal JSON parsing for POST /commands (very basic, not production-safe)
    private static Map<String, Object> parseJson(String json) {
        // For brevity, only handles flat JSON: {"key":"val"}
        Map<String, Object> map = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return map;
            String[] pairs = json.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replaceAll("^\"|\"$", "");
                    String val = kv[1].trim();
                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                        map.put(key, val);
                    } else if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)) {
                        map.put(key, Boolean.parseBoolean(val));
                    } else {
                        try {
                            if (val.contains(".")) {
                                map.put(key, Double.parseDouble(val));
                            } else {
                                map.put(key, Integer.parseInt(val));
                            }
                        } catch (NumberFormatException e) {
                            map.put(key, val);
                        }
                    }
                }
            }
        }
        return map;
    }
}