import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class DeviceShifuDriver {

    // Environment variable keys
    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";

    // Dummy data for illustration (replace with actual device interaction logic)
    private static final List<Map<String, Object>> DATA_POINTS = Collections.synchronizedList(new ArrayList<>());

    static {
        // Populate with some fake data for demonstration
        for (int i = 1; i <= 50; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("timestamp", System.currentTimeMillis());
            point.put("value", Math.random() * 100);
            DATA_POINTS.add(point);
        }
    }

    public static void main(String[] args) throws IOException {
        String host = System.getenv(ENV_SERVER_HOST);
        String portStr = System.getenv(ENV_SERVER_PORT);

        if (host == null || host.isEmpty()) host = "0.0.0.0";
        int port = 8080;
        if (portStr != null) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {}
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("DeviceShifu Driver HTTP Server started at http://" + host + ":" + port + " ...");
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            // Parse query params
            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
            int page = parseIntOrDefault(queryParams.get("page"), 1);
            int limit = parseIntOrDefault(queryParams.get("limit"), 10);

            // Pagination logic
            int start = (page - 1) * limit;
            int end = Math.min(start + limit, DATA_POINTS.size());
            List<Map<String, Object>> pagedData;
            if (start >= DATA_POINTS.size()) {
                pagedData = Collections.emptyList();
            } else {
                pagedData = DATA_POINTS.subList(start, end);
            }

            // Filter logic (optional, e.g., by value)
            // Example: /points?min=30
            if (queryParams.containsKey("min")) {
                double minValue = parseDoubleOrDefault(queryParams.get("min"), Double.MIN_VALUE);
                pagedData = pagedData.stream()
                        .filter(dp -> ((Number) dp.get("value")).doubleValue() >= minValue)
                        .collect(Collectors.toList());
            }
            if (queryParams.containsKey("max")) {
                double maxValue = parseDoubleOrDefault(queryParams.get("max"), Double.MAX_VALUE);
                pagedData = pagedData.stream()
                        .filter(dp -> ((Number) dp.get("value")).doubleValue() <= maxValue)
                        .collect(Collectors.toList());
            }

            // Compose JSON response
            String jsonResponse = serializePointsResponse(pagedData, page, limit, DATA_POINTS.size());
            sendResponse(exchange, 200, jsonResponse, "application/json");
        }
    }

    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            InputStream is = exchange.getRequestBody();
            String body = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            is.close();

            // For demonstration: just echo back the command and fake a result
            // In real implementation, parse and send command to device
            String result = "{ \"status\": \"success\", \"received\": " + body + " }";
            sendResponse(exchange, 200, result, "application/json");
        }
    }

    // Utility: parse query string into map
    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0 && idx < pair.length() - 1) {
                String key = decodeUrlComponent(pair.substring(0, idx));
                String value = decodeUrlComponent(pair.substring(idx + 1));
                params.put(key, value);
            }
        }
        return params;
    }

    private static String decodeUrlComponent(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static int parseIntOrDefault(String value, int def) {
        if (value == null) return def;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static double parseDoubleOrDefault(String value, double def) {
        if (value == null) return def;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    // Simple JSON serialization for points
    private static String serializePointsResponse(List<Map<String, Object>> points, int page, int limit, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"page\":").append(page).append(",");
        sb.append("\"limit\":").append(limit).append(",");
        sb.append("\"total\":").append(total).append(",");
        sb.append("\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> p = points.get(i);
            sb.append("{");
            int j = 0;
            for (Map.Entry<String, Object> entry : p.entrySet()) {
                sb.append("\"").append(entry.getKey()).append("\":");
                if (entry.getValue() instanceof Number) {
                    sb.append(entry.getValue());
                } else {
                    sb.append("\"").append(entry.getValue().toString()).append("\"");
                }
                if (++j < p.size()) sb.append(",");
            }
            sb.append("}");
            if (i < points.size() - 1) sb.append(",");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void sendResponse(HttpExchange exchange, int status, String content, String contentType) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
}