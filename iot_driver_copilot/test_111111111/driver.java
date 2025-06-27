import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class DeviceShifuDriver {
    // Example in-memory telemetry and commands data for demonstration
    private static final List<Map<String, Object>> telemetryData = Collections.synchronizedList(new ArrayList<>());
    private static final List<Map<String, Object>> commandHistory = Collections.synchronizedList(new ArrayList<>());

    static {
        // Initial fake data points
        Map<String, Object> dp1 = new HashMap<>();
        dp1.put("timestamp", System.currentTimeMillis());
        dp1.put("temperature", 23.5);
        dp1.put("humidity", 56.2);
        telemetryData.add(dp1);

        Map<String, Object> dp2 = new HashMap<>();
        dp2.put("timestamp", System.currentTimeMillis());
        dp2.put("temperature", 24.1);
        dp2.put("humidity", 55.0);
        telemetryData.add(dp2);
    }

    public static void main(String[] args) throws IOException {
        String serverHost = System.getenv().getOrDefault("SHIFU_HTTP_SERVER_HOST", "0.0.0.0");
        int serverPort = Integer.parseInt(System.getenv().getOrDefault("SHIFU_HTTP_SERVER_PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(serverHost, serverPort), 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.printf("DeviceShifuDriver HTTP server started at http://%s:%d%n", serverHost, serverPort);
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> queryParams = queryToMap(exchange.getRequestURI().getRawQuery());
            int page = 1;
            int limit = 10;

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

            int fromIndex = Math.max((page - 1) * limit, 0);
            int toIndex = Math.min(fromIndex + limit, telemetryData.size());

            List<Map<String, Object>> paginated = Collections.emptyList();
            if (fromIndex < toIndex) {
                synchronized (telemetryData) {
                    paginated = new ArrayList<>(telemetryData.subList(fromIndex, toIndex));
                }
            }
            Map<String, Object> responseMap = new LinkedHashMap<>();
            responseMap.put("page", page);
            responseMap.put("limit", limit);
            responseMap.put("total", telemetryData.size());
            responseMap.put("data", paginated);

            String responseJson = toJson(responseMap);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponse(exchange, 200, responseJson);
        }
    }

    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
            Map<String, Object> command;
            try {
                command = parseJsonToMap(body);
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid JSON payload\"}");
                return;
            }

            // Simulate execution and store in history
            command.put("executedAt", System.currentTimeMillis());
            synchronized (commandHistory) {
                commandHistory.add(command);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "success");
            resp.put("executedCommand", command);

            String responseJson = toJson(resp);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            sendResponse(exchange, 200, responseJson);
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String resp) throws IOException {
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.trim().isEmpty()) return map;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 1) {
                map.put(urlDecode(pair[0]), urlDecode(pair[1]));
            } else if (pair.length == 1) {
                map.put(urlDecode(pair[0]), "");
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        byte[] buf = new byte[4096];
        int read;
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        while ((read = is.read(buf)) != -1) {
            chunks.add(Arrays.copyOf(buf, read));
            total += read;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.length);
            pos += chunk.length;
        }
        return out;
    }

    // Minimal JSON serializer for demonstration
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) obj).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                sb.append("\"").append(escapeJson(e.getKey().toString())).append("\":")
                  .append(toJson(e.getValue()));
                if (it.hasNext()) sb.append(",");
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            Iterator<?> it = ((List<?>) obj).iterator();
            while (it.hasNext()) {
                sb.append(toJson(it.next()));
                if (it.hasNext()) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String) {
            return "\"" + escapeJson((String) obj) + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        } else if (obj == null) {
            return "null";
        } else {
            return "\"" + escapeJson(obj.toString()) + "\"";
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // Minimal JSON parser for simple flat JSON objects (no arrays)
    private static Map<String, Object> parseJsonToMap(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) throw new IllegalArgumentException("Not an object");
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return map;
        String[] pairs = inner.split(",");
        for (String pair : pairs) {
            String[] keyVal = pair.split(":", 2);
            if (keyVal.length != 2) continue;
            String key = keyVal[0].trim();
            String val = keyVal[1].trim();
            if (key.startsWith("\"") && key.endsWith("\"")) key = key.substring(1, key.length() - 1);
            if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
            else if ("null".equals(val)) val = null;
            map.put(key, val);
        }
        return map;
    }
}