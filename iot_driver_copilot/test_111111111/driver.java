```java
package com.deviceshifu.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;

import javax.servlet.ServletException;
import javax.servlet.http.*;
import javax.servlet.annotation.WebServlet;
import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class DeviceShifuDriver {

    // In-memory simulated data points and command logs
    private static final List<Map<String, Object>> dataPoints = new CopyOnWriteArrayList<>();
    private static final List<Map<String, Object>> commandLogs = new CopyOnWriteArrayList<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Device connection/configuration from environment variables
    private static final String DEVICE_IP = System.getenv("DEVICE_IP");
    private static final String SERVER_HOST = System.getenv("SERVER_HOST") != null ? System.getenv("SERVER_HOST") : "0.0.0.0";
    private static final int SERVER_PORT = System.getenv("SERVER_PORT") != null ? Integer.parseInt(System.getenv("SERVER_PORT")) : 8080;

    public static void main(String[] args) throws Exception {
        // Populate example data point
        Map<String, Object> examplePoint = new HashMap<>();
        examplePoint.put("timestamp", System.currentTimeMillis());
        examplePoint.put("temperature", 23.5);
        examplePoint.put("humidity", 50);
        dataPoints.add(examplePoint);

        // Start embedded Jetty HTTP server
        org.eclipse.jetty.server.Server server = new org.eclipse.jetty.server.Server(SERVER_PORT);

        org.eclipse.jetty.servlet.ServletContextHandler context = new org.eclipse.jetty.servlet.ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(PointsServlet.class, "/points");
        context.addServlet(CommandsServlet.class, "/commands");

        server.setHandler(context);
        server.start();
        server.join();
    }

    @WebServlet(name = "PointsServlet", urlPatterns = {"/points"})
    public static class PointsServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("application/json");
            int page = 1, limit = 10;
            String pageStr = req.getParameter("page");
            String limitStr = req.getParameter("limit");
            if (pageStr != null) {
                try { page = Integer.parseInt(pageStr); } catch (NumberFormatException ignored) {}
            }
            if (limitStr != null) {
                try { limit = Integer.parseInt(limitStr); } catch (NumberFormatException ignored) {}
            }

            int fromIndex = (page - 1) * limit;
            int toIndex = Math.min(fromIndex + limit, dataPoints.size());
            List<Map<String, Object>> pagedData;
            if (fromIndex < dataPoints.size()) {
                pagedData = dataPoints.subList(fromIndex, toIndex);
            } else {
                pagedData = Collections.emptyList();
            }
            Map<String, Object> response = new HashMap<>();
            response.put("data", pagedData);
            response.put("page", page);
            response.put("limit", limit);
            response.put("total", dataPoints.size());
            resp.getWriter().write(objectMapper.writeValueAsString(response));
        }
    }

    @WebServlet(name = "CommandsServlet", urlPatterns = {"/commands"})
    public static class CommandsServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("application/json");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = req.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            Map<String, Object> command;
            try {
                command = objectMapper.readValue(sb.toString(), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.getWriter().write("{\"error\": \"Invalid JSON payload.\"}");
                return;
            }
            command.put("executedAt", System.currentTimeMillis());
            commandLogs.add(command);

            // Simulate effect to device (update data point)
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("command", command);

            resp.getWriter().write(objectMapper.writeValueAsString(result));
        }
    }
}
```
