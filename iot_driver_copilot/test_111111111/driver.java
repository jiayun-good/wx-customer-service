import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

public class DeviceShifuHttpDriver {

    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_MODEL = "DEVICE_MODEL";
    private static final String ENV_DEVICE_NAME = "DEVICE_NAME";
    private static final String ENV_DEVICE_TYPE = "DEVICE_TYPE";
    private static final String ENV_MANUFACTURER = "MANUFACTURER";

    // Mock device data for demonstration
    private static final List<Map<String, Object>> MOCK_DATA_POINTS = new ArrayList<>();

    static {
        // Populate with some example telemetry data points
        for (int i = 1; i <= 25; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("timestamp", System.currentTimeMillis());
            point.put("value", Math.random() * 100);
            point.put("status", "OK");
            MOCK_DATA_POINTS.add(point);
        }
    }

    public static void main(String[] args) throws Exception {
        String host = getEnvOrDefault(ENV_SERVER_HOST, "0.0.0.0");
        int port = Integer.parseInt(getEnvOrDefault(ENV_SERVER_PORT, "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("DeviceShifu HTTP Driver started at http://%s:%d%n", host, port);
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> queryParams = parseQuery(exchange.getRequestURI());
            int page = parseInt(queryParams.getOrDefault("page", "1"), 1);
            int limit = parseInt(queryParams.getOrDefault("limit", "10"), 10);

            int fromIndex = Math.max((page - 1) * limit, 0);
            int toIndex = Math.min(fromIndex + limit, MOCK_DATA_POINTS.size());

            List<Map<String, Object>> pageData = fromIndex >= MOCK_DATA_POINTS.size()
                    ? Collections.emptyList()
                    : MOCK_DATA_POINTS.subList(fromIndex, toIndex);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("device", getDeviceMeta());
            result.put("page", page);
            result.put("limit", limit);
            result.put("total", MOCK_DATA_POINTS.size());
            result.put("data", pageData);

            String json = toJson(result);

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            sendResponse(exchange, 200, json);
        }
    }

    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String requestBody = readRequestBody(exchange.getRequestBody());
            Map<String, Object> command;
            try {
                command = parseJson(requestBody);
            } catch (Exception e) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid JSON payload.\"}");
                return;
            }
            // Here, you would interact with the actual device with the command payload.
            // For demonstration, just echo back received command.
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("received", command);
            response.put("timestamp", System.currentTimeMillis());
            String json = toJson(response);

            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            sendResponse(exchange, 200, json);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> queryPairs = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null) return queryPairs;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                queryPairs.put(
                        idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair,
                        idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : ""
                );
            } catch (UnsupportedEncodingException ignored) {}
        }
        return queryPairs;
    }

    private static int parseInt(String val, int defaultVal) {
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static void sendResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private static String readRequestBody(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int read;
        while ((read = is.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private static Map<String, Object> getDeviceMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("device_name", System.getenv(ENV_DEVICE_NAME));
        meta.put("device_model", System.getenv(ENV_DEVICE_MODEL));
        meta.put("manufacturer", System.getenv(ENV_MANUFACTURER));
        meta.put("device_type", System.getenv(ENV_DEVICE_TYPE));
        meta.put("device_ip", System.getenv(ENV_DEVICE_IP));
        return meta;
    }

    // Minimal JSON serialization for demonstration (no third-party libraries)
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
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
            return "\"" + escapeJson(obj.toString()) + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return String.valueOf(obj);
        } else if (obj == null) {
            return "null";
        } else {
            return "\"" + escapeJson(obj.toString()) + "\"";
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b")
                .replace("\f", "\\f").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // Minimal JSON parser for demonstration (valid for simple flat JSON objects)
    private static Map<String, Object> parseJson(String json) throws Exception {
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) throw new Exception("Invalid JSON");
        Map<String, Object> map = new HashMap<>();
        String body = json.substring(1, json.length() - 1);
        String[] pairs = body.split(",");
        for (String pair : pairs) {
            if (pair.trim().isEmpty()) continue;
            int idx = pair.indexOf(":");
            if (idx == -1) continue;
            String key = pair.substring(0, idx).trim().replaceAll("^\"|\"$", "");
            String value = pair.substring(idx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                map.put(key, value.substring(1, value.length() - 1));
            } else if ("null".equals(value)) {
                map.put(key, null);
            } else if ("true".equals(value) || "false".equals(value)) {
                map.put(key, Boolean.valueOf(value));
            } else {
                try {
                    if (value.contains(".")) {
                        map.put(key, Double.valueOf(value));
                    } else {
                        map.put(key, Long.valueOf(value));
                    }
                } catch (Exception e) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private static String getEnvOrDefault(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.trim().isEmpty() ? def : v;
    }
}