```java
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

// Main class
public class DeviceShifuDriver {

    // Mock device data (in a real driver, retrieve from the device)
    private static final List<Map<String, Object>> MOCK_POINTS = new ArrayList<>();

    static {
        // Populate mock telemetry data
        for (int i = 1; i <= 25; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("name", "datapoint_" + i);
            point.put("value", Math.round(Math.random() * 100));
            point.put("timestamp", System.currentTimeMillis());
            MOCK_POINTS.add(point);
        }
    }

    public static void main(String[] args) throws Exception {
        // Config from environment variables
        String host = System.getenv().getOrDefault("DEVICE_SHIFU_HTTP_HOST", "0.0.0.0");
        int port = Integer.parseInt(System.getenv().getOrDefault("DEVICE_SHIFU_HTTP_PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("DeviceShifu HTTP Driver started at http://" + host + ":" + port);
    }

    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}", "application/json");
                return;
            }

            // Parse query params for pagination/filtering
            URI uri = exchange.getRequestURI();
            String query = uri.getRawQuery();
            int page = 1;
            int limit = 10;

            if (query != null) {
                Map<String, String> params = parseQuery(query);
                if (params.containsKey("page")) {
                    try { page = Integer.parseInt(params.get("page")); } catch (Exception ignored) {}
                }
                if (params.containsKey("limit")) {
                    try { limit = Integer.parseInt(params.get("limit")); } catch (Exception ignored) {}
                }
            }

            int fromIndex = (page - 1) * limit;
            int toIndex = Math.min(fromIndex + limit, MOCK_POINTS.size());
            JSONArray points = new JSONArray();

            if (fromIndex < MOCK_POINTS.size()) {
                for (int i = fromIndex; i < toIndex; i++) {
                    points.put(new JSONObject(MOCK_POINTS.get(i)));
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("page", page);
            resp.put("limit", limit);
            resp.put("total", MOCK_POINTS.size());
            resp.put("data", points);

            sendResponse(exchange, 200, resp.toString(), "application/json");
        }
    }

    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}", "application/json");
                return;
            }

            String body = readRequestBody(exchange);
            JSONObject resp = new JSONObject();

            try {
                JSONObject command = new JSONObject(body);
                // Here, you would implement actual device command execution logic.
                resp.put("status", "success");
                resp.put("received", command);
                resp.put("message", "Command executed (mock)");
            } catch (Exception e) {
                resp.put("status", "error");
                resp.put("message", "Invalid JSON: " + e.getMessage());
                sendResponse(exchange, 400, resp.toString(), "application/json");
                return;
            }

            sendResponse(exchange, 200, resp.toString(), "application/json");
        }
    }

    // Utility methods

    private static void sendResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType + "; charset=utf-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = is.read(buffer)) != -1) {
            baos.write(buffer, 0, length);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0 && idx < pair.length() - 1) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return params;
    }
}
```
