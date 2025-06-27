import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class DeviceShifuDriver {

    // Simulated Data Points (for demonstration)
    private static List<Map<String, Object>> dataPoints = Collections.synchronizedList(new ArrayList<>());

    static {
        // Mock data points
        for (int i = 1; i <= 25; i++) {
            Map<String, Object> point = new HashMap<>();
            point.put("id", i);
            point.put("timestamp", System.currentTimeMillis() - i * 1000);
            point.put("value", Math.random() * 100);
            dataPoints.add(point);
        }
    }

    public static void main(String[] args) throws IOException {
        // Configurable via environment variables
        String host = System.getenv().getOrDefault("SERVER_HOST", "0.0.0.0");
        int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("DeviceShifuDriver HTTP server started on " + host + ":" + port);
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Map<String, String> queryParams = queryToMap(exchange.getRequestURI().getRawQuery());
            int page = parseIntOrDefault(queryParams.get("page"), 1);
            int limit = parseIntOrDefault(queryParams.get("limit"), 10);

            List<Map<String, Object>> filteredPoints;
            synchronized (dataPoints) {
                int fromIdx = Math.max(0, (page - 1) * limit);
                int toIdx = Math.min(dataPoints.size(), fromIdx + limit);
                filteredPoints = dataPoints.subList(fromIdx, toIdx);
            }

            JSONObject resp = new JSONObject();
            resp.put("page", page);
            resp.put("limit", limit);
            resp.put("total", dataPoints.size());
            resp.put("points", filteredPoints);

            sendJsonResponse(exchange, 200, resp.toString());
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

            String body = readRequestBody(exchange.getRequestBody());
            JSONObject jsonResp = new JSONObject();
            try {
                JSONObject commandReq = new JSONObject(body);

                // Simulate command execution (mock)
                String command = commandReq.optString("command", "unknown");
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("executed_command", command);
                result.put("timestamp", System.currentTimeMillis());

                jsonResp.put("result", result);
            } catch (Exception e) {
                jsonResp.put("error", "Invalid JSON or command format");
                sendJsonResponse(exchange, 400, jsonResp.toString());
                return;
            }
            sendJsonResponse(exchange, 200, jsonResp.toString());
        }
    }

    // Utility: Parse query string to Map
    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;
        for (String param : query.split("&")) {
            if (param.isEmpty()) continue;
            String[] entry = param.split("=", 2);
            if (entry.length == 2) {
                result.put(decode(entry[0]), decode(entry[1]));
            } else {
                result.put(decode(entry[0]), "");
            }
        }
        return result;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // Utility: Parse int with default
    private static int parseIntOrDefault(String val, int def) {
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return def;
        }
    }

    // Utility: Read request body as String
    private static String readRequestBody(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }

    // Utility: Send JSON response
    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=UTF-8");
        sendResponse(exchange, statusCode, body);
    }

    // Utility: Send plain response
    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}