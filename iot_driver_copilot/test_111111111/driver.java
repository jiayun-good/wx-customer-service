import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DeviceShifuDriver {
    // Environment variable names
    private static final String ENV_SERVER_HOST = "DEVICE_SHIFU_HTTP_SERVER_HOST";
    private static final String ENV_SERVER_PORT = "DEVICE_SHIFU_HTTP_SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_SHIFU_DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_SHIFU_DEVICE_PORT"; // if needed

    // Example in-memory data to simulate device telemetry data and commands
    private static List<Map<String, Object>> deviceDataPoints = Collections.synchronizedList(new ArrayList<>());
    private static List<Map<String, Object>> deviceCommands = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws IOException {
        String host = getEnvOrDefault(ENV_SERVER_HOST, "0.0.0.0");
        int port = Integer.parseInt(getEnvOrDefault(ENV_SERVER_PORT, "8080"));
        // Device IP and PORT for real device communication, simulated here
        String deviceIp = getEnvOrDefault(ENV_DEVICE_IP, "127.0.0.1");
        String devicePort = getEnvOrDefault(ENV_DEVICE_PORT, "12345");

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
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
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            // Simulate fetching data from the device (would be protocol-specific IO in real driver)
            List<Map<String, Object>> data = fetchDeviceDataPoints();

            // Handle query params for pagination/filtering
            URI uri = exchange.getRequestURI();
            Map<String, String> queryParams = queryToMap(uri.getRawQuery());
            int page = parseIntOrDefault(queryParams.get("page"), 1);
            int limit = parseIntOrDefault(queryParams.get("limit"), 10);

            int fromIndex = Math.max(0, (page - 1) * limit);
            int toIndex = Math.min(data.size(), fromIndex + limit);
            List<Map<String, Object>> pageData = fromIndex < data.size() ? data.subList(fromIndex, toIndex) : Collections.emptyList();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", data.size());
            response.put("data", pageData);

            sendJsonResponse(exchange, 200, response);
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

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
                sendResponse(exchange, 400, "Content-Type must be application/json");
                return;
            }

            String body = readRequestBody(exchange);
            Map<String, Object> command;
            try {
                command = parseJson(body);
            } catch (Exception e) {
                sendResponse(exchange, 400, "Invalid JSON payload");
                return;
            }

            // Simulate sending command to device (would be protocol-specific IO in real driver)
            boolean success = sendDeviceCommand(command);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            result.put("received", command);

            sendJsonResponse(exchange, 200, result);
        }
    }

    // --- Simulated Device Logic ---

    private static List<Map<String, Object>> fetchDeviceDataPoints() {
        // For demo: generate random data if empty
        if (deviceDataPoints.isEmpty()) {
            for (int i = 1; i <= 42; i++) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", i);
                point.put("timestamp", System.currentTimeMillis());
                point.put("value", Math.random() * 100);
                deviceDataPoints.add(point);
            }
        }
        return new ArrayList<>(deviceDataPoints);
    }

    private static boolean sendDeviceCommand(Map<String, Object> command) {
        // For demo: simply store the command and return success
        deviceCommands.add(new LinkedHashMap<>(command));
        return true;
    }

    // --- Utilities ---

    private static String getEnvOrDefault(String env, String defaultVal) {
        String value = System.getenv(env);
        return (value != null && !value.isEmpty()) ? value : defaultVal;
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String param : query.split("&")) {
            String[] entry = param.split("=", 2);
            if (entry.length > 0) {
                String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                String value = entry.length > 1 ? URLDecoder.decode(entry[1], StandardCharsets.UTF_8) : "";
                map.put(key, value);
            }
        }
        return map;
    }

    private static int parseIntOrDefault(String val, int defaultVal) {
        try {
            return val == null ? defaultVal : Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, Map<String, Object> data) throws IOException {
        String json = toJson(data);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    // --- Minimal JSON serialization/deserialization (no third-party libraries) ---

    // Very naive JSON parser for demo purposes (assumes flat JSON object, string keys, primitive values)
    private static Map<String, Object> parseJson(String json) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}"))
            throw new IOException("Invalid JSON object");
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return map;
        for (String pair : json.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim();
            String value = kv[1].trim();
            key = stripQuotes(key);
            if (value.startsWith("\"") && value.endsWith("\"")) {
                map.put(key, stripQuotes(value));
            } else if (value.equals("null")) {
                map.put(key, null);
            } else if (value.equals("true") || value.equals("false")) {
                map.put(key, Boolean.parseBoolean(value));
            } else {
                try {
                    if (value.contains("."))
                        map.put(key, Double.parseDouble(value));
                    else
                        map.put(key, Long.parseLong(value));
                } catch (NumberFormatException e) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
            return s.substring(1, s.length() - 1);
        return s;
    }

    // Very naive JSON serializer for demo purposes
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) {
            return "\"" + escapeJson((String) obj) + "\"";
        }
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Object entryObj : ((Map<?, ?>) obj).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObj;
                if (!first) sb.append(",");
                sb.append(toJson(entry.getKey().toString()));
                sb.append(":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (obj instanceof Collection) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object item : (Collection<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(obj.toString()) + "\"";
    }

    private static String escapeJson(String str) {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}