import com.sun.net.httpserver.Headers;
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
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";

    // Simulated data point store
    private static final List<Map<String, Object>> DATA_POINTS = Collections.synchronizedList(new LinkedList<>());
    private static final int DEFAULT_PAGE_SIZE = 10;

    public static void main(String[] args) throws IOException {
        // Read configuration from environment variables
        String serverHost = System.getenv(ENV_SERVER_HOST);
        String serverPortStr = System.getenv(ENV_SERVER_PORT);
        String deviceIp = System.getenv(ENV_DEVICE_IP);
        String devicePortStr = System.getenv(ENV_DEVICE_PORT);

        if (serverHost == null || serverHost.isEmpty()) serverHost = "0.0.0.0";
        int serverPort = 8080;
        try {
            if (serverPortStr != null && !serverPortStr.isEmpty()) {
                serverPort = Integer.parseInt(serverPortStr);
            }
        } catch (NumberFormatException ignored) {
        }

        // Dummy device data population for demonstration
        populateDummyData();

        // Set up HTTP server
        InetSocketAddress address = new InetSocketAddress(serverHost, serverPort);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("DeviceShifu HTTP driver started on %s:%d%n", serverHost, serverPort);
    }

    // Populate with dummy device data (simulate real device fetch)
    private static void populateDummyData() {
        for (int i = 1; i <= 100; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("timestamp", System.currentTimeMillis() - (100 - i) * 1000L);
            point.put("value", Math.random() * 100);
            point.put("status", "OK");
            DATA_POINTS.add(point);
        }
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            // Parse query params for pagination
            Map<String, String> params = queryToMap(exchange.getRequestURI().getRawQuery());
            int page = parseIntWithDefault(params.get("page"), 1);
            int limit = parseIntWithDefault(params.get("limit"), DEFAULT_PAGE_SIZE);

            // Filter and paginate
            int total = DATA_POINTS.size();
            int fromIdx = Math.max(0, (page - 1) * limit);
            int toIdx = Math.min(fromIdx + limit, total);

            List<Map<String, Object>> subList = new ArrayList<>();
            if (fromIdx < toIdx) {
                subList.addAll(DATA_POINTS.subList(fromIdx, toIdx));
            }

            // Build JSON response
            String responseJson = buildPointsResponse(subList, page, limit, total);

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            sendResponse(exchange, 200, responseJson);
        }

        private String buildPointsResponse(List<Map<String, Object>> points, int page, int limit, int total) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"page\":").append(page).append(",");
            sb.append("\"limit\":").append(limit).append(",");
            sb.append("\"total\":").append(total).append(",");
            sb.append("\"data\":[");
            boolean first = true;
            for (Map<String, Object> point : points) {
                if (!first) sb.append(",");
                sb.append(mapToJson(point));
                first = false;
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    // Handler for POST /commands
    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");

            String requestBody = readRequestBody(exchange);
            if (requestBody == null || requestBody.isEmpty()) {
                sendResponse(exchange, 400, "{\"error\":\"Empty request body\"}");
                return;
            }

            // Simulate command handling; echo back received command
            String responseJson = "{\"result\":\"Command received\",\"command\":" + escapeJson(requestBody) + "}";
            sendResponse(exchange, 200, responseJson);
        }
    }

    // Utility Functions

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object v = entry.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v.toString());
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static int parseIntWithDefault(String s, int defaultValue) {
        try {
            if (s != null) return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
        }
        return defaultValue;
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> res = new HashMap<>();
        if (query == null || query.isEmpty()) return res;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx == -1) continue;
            res.put(urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)));
        }
        return res;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return sb.toString().trim();
    }

    private static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}