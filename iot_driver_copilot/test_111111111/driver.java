import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class DeviceShifuDriver {
    // Environment variable keys
    private static final String ENV_SERVER_HOST = "SERVER_HOST";
    private static final String ENV_SERVER_PORT = "SERVER_PORT";
    private static final String ENV_DEVICE_IP = "DEVICE_IP";
    private static final String ENV_DEVICE_PORT = "DEVICE_PORT";

    // Example stub data for demonstration (replace with real device interaction)
    private static final List<Map<String, Object>> DEVICE_POINTS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        String host = System.getenv(ENV_SERVER_HOST);
        String portStr = System.getenv(ENV_SERVER_PORT);
        String deviceIp = System.getenv(ENV_DEVICE_IP);
        String devicePort = System.getenv(ENV_DEVICE_PORT);

        if (host == null || host.isEmpty()) host = "0.0.0.0";
        int port = 8080;
        if (portStr != null && !portStr.isEmpty()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                System.err.println("Invalid SERVER_PORT env, default to 8080");
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(null); // default executor
        System.out.println("DeviceShifuDriver running on " + host + ":" + port);
        server.start();
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            // Query parameters: page, limit
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            int page = 1, limit = 10;
            try {
                if (params.containsKey("page")) page = Integer.parseInt(params.get("page"));
                if (params.containsKey("limit")) limit = Integer.parseInt(params.get("limit"));
            } catch (NumberFormatException ignored) {}

            // Simulate device data acquisition: this should fetch real device telemetry
            List<Map<String, Object>> allPoints = getDevicePoints();

            int from = (page - 1) * limit;
            int to = Math.min(from + limit, allPoints.size());
            List<Map<String, Object>> pagedPoints = from >= allPoints.size() ? Collections.emptyList() : allPoints.subList(from, to);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("page", page);
            result.put("limit", limit);
            result.put("total", allPoints.size());
            result.put("data", pagedPoints);

            String respJson = toJson(result);
            sendResponse(exchange, 200, respJson, "application/json");
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
            String body = readRequestBody(exchange.getRequestBody());
            // In real implementation, parse and execute command on device
            // Here, we just echo the received command and simulate a success response
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "success");
            resp.put("received", body);
            String respJson = toJson(resp);
            sendResponse(exchange, 200, respJson, "application/json");
        }
    }

    // Simulated device point acquisition
    private static List<Map<String, Object>> getDevicePoints() {
        // For demonstration, generate some fake points if empty
        if (DEVICE_POINTS.isEmpty()) {
            for (int i = 1; i <= 25; ++i) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", i);
                point.put("timestamp", System.currentTimeMillis());
                point.put("value", Math.random() * 100);
                DEVICE_POINTS.add(point);
            }
        }
        return DEVICE_POINTS;
    }

    // Util: Parse query string into map
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

    // Util: Read request body as string
    private static String readRequestBody(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int read;
        while ((read = is.read(buf)) != -1) {
            bos.write(buf, 0, read);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // Util: Send HTTP response
    private static void sendResponse(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    // Minimal JSON serialization (for demonstration)
    private static String toJson(Object obj) {
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            Map<?, ?> map = (Map<?, ?>) obj;
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
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
            return "\"" + escapeJson((String) obj) + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        } else if (obj == null) {
            return "null";
        }
        return "\"" + escapeJson(String.valueOf(obj)) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}