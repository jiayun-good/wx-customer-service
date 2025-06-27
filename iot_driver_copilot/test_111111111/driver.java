import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DeviceShifuDriver {
    // Environment variables
    private static final String ENV_HOST = "SERVER_HOST";
    private static final String ENV_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";

    // Simulated telemetry data for demonstration
    private static final List<Map<String, Object>> telemetryData = new ArrayList<>();

    static {
        // Populate with dummy data points
        for (int i = 1; i <= 100; i++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", i);
            point.put("temperature", 20 + i % 10);
            point.put("humidity", 50 + i % 5);
            point.put("timestamp", System.currentTimeMillis() - i * 1000L);
            telemetryData.add(point);
        }
    }

    public static void main(String[] args) throws IOException {
        String host = System.getenv(ENV_HOST);
        String portStr = System.getenv(ENV_PORT);

        if (host == null || portStr == null) {
            System.err.println("Required environment variables: SERVER_HOST, SERVER_PORT");
            System.exit(1);
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            System.err.println("SERVER_PORT must be a valid number.");
            System.exit(1);
            return;
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("DeviceShifu HTTP driver running at http://" + host + ":" + port);
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
            int page = parseIntOrDefault(queryParams.get("page"), 1);
            int limit = parseIntOrDefault(queryParams.get("limit"), 10);

            int fromIndex = (page - 1) * limit;
            int toIndex = Math.min(fromIndex + limit, telemetryData.size());

            List<Map<String, Object>> result = new ArrayList<>();
            if (fromIndex < telemetryData.size()) {
                result = telemetryData.subList(fromIndex, toIndex);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", telemetryData.size());
            response.put("data", result);

            String json = toJson(response);
            sendResponse(exchange, 200, json, "application/json");
        }
    }

    // Handler for POST /commands
    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
                sendResponse(exchange, 400, "{\"error\": \"Content-Type must be application/json\"}", "application/json");
                return;
            }

            String body = readRequestBody(exchange);
            if (body == null || body.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\": \"Empty request body\"}", "application/json");
                return;
            }

            // Simulate device command execution and response
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("message", "Command executed");
            response.put("received", body);

            sendResponse(exchange, 200, toJson(response), "application/json");
        }
    }

    // Utility: Parse query parameters
    private static Map<String, String> parseQueryParams(String rawQuery) throws UnsupportedEncodingException {
        Map<String, String> queryParams = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return queryParams;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > -1) {
                queryParams.put(
                        URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                );
            } else {
                queryParams.put(
                        URLDecoder.decode(pair, StandardCharsets.UTF_8.name()),
                        ""
                );
            }
        }
        return queryParams;
    }

    // Utility: Parse int with default
    private static int parseIntOrDefault(String val, int def) {
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return def;
        }
    }

    // Utility: Read request body
    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(data)) != -1) {
            buf.write(data, 0, bytesRead);
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    // Utility: Send HTTP response
    private static void sendResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=utf-8");
        byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
    }

    // Utility: Very simple JSON serialization (for demonstration; a real implementation should use a JSON library)
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escape(entry.getKey().toString())).append("\":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object item : (List<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String) {
            return "\"" + escape((String) obj) + "\"";
        } else {
            return String.valueOf(obj);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}