#include <iostream>
#include <cstdlib>
#include <string>
#include <sstream>
#include <map>
#include <vector>
#include <algorithm>
#include <cstring>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <netinet/in.h>
#include <unistd.h>

#define BUFFER_SIZE 8192

// Utility: Read env variable or use default
std::string getenv_or_default(const char* name, const char* defval) {
    const char* val = std::getenv(name);
    return val ? std::string(val) : std::string(defval);
}

// XML Mock Data Example (would be replaced by actual device communication)
std::string generate_device_data_xml(const std::map<std::string, std::string>& params) {
    std::ostringstream oss;
    oss << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    oss << "<device_data>\n";
    oss << "  <device_name>test1</device_name>\n";
    oss << "  <device_model>dsa</device_model>\n";
    oss << "  <manufacturer>adsads</manufacturer>\n";
    oss << "  <device_type>ads</device_type>\n";
    oss << "  <data_points>adsads</data_points>\n";
    if (!params.empty()) {
        oss << "  <query>\n";
        for (const auto& kv : params) {
            oss << "    <" << kv.first << ">" << kv.second << "</" << kv.first << ">\n";
        }
        oss << "  </query>\n";
    }
    oss << "</device_data>";
    return oss.str();
}

// JSON Response for command
std::string generate_command_response_json(const std::string& action, bool accepted) {
    std::ostringstream oss;
    oss << "{\n";
    oss << "  \"command\": \"" << action << "\",\n";
    oss << "  \"status\": \"" << (accepted ? "accepted" : "rejected") << "\",\n";
    oss << "  \"executed\": " << (accepted ? "true" : "false") << "\n";
    oss << "}";
    return oss.str();
}

// Parse query string into map
std::map<std::string, std::string> parse_query(const std::string& query) {
    std::map<std::string, std::string> params;
    std::istringstream ss(query);
    std::string token;
    while (std::getline(ss, token, '&')) {
        auto pos = token.find('=');
        if (pos != std::string::npos) {
            params[token.substr(0, pos)] = token.substr(pos + 1);
        }
    }
    return params;
}

// Parse HTTP headers
void parse_headers(const std::string& header_str, std::map<std::string, std::string>& headers) {
    std::istringstream stream(header_str);
    std::string line;
    while (std::getline(stream, line) && line != "\r") {
        auto pos = line.find(':');
        if (pos != std::string::npos) {
            std::string key = line.substr(0, pos);
            std::string val = line.substr(pos + 1);
            val.erase(0, val.find_first_not_of(" \t\r\n"));
            key.erase(std::remove(key.begin(), key.end(), '\r'), key.end());
            headers[key] = val;
        }
    }
}

// Parse HTTP request line and headers
struct HttpRequest {
    std::string method;
    std::string path;
    std::string query;
    std::string http_version;
    std::map<std::string, std::string> headers;
    std::string body;
};

HttpRequest parse_http_request(const std::string& req) {
    HttpRequest hr;
    size_t method_end = req.find(' ');
    size_t path_end = req.find(' ', method_end + 1);
    size_t version_end = req.find("\r\n");
    if (method_end == std::string::npos || path_end == std::string::npos || version_end == std::string::npos) {
        return hr;
    }
    hr.method = req.substr(0, method_end);
    std::string path_query = req.substr(method_end + 1, path_end - method_end - 1);
    hr.http_version = req.substr(path_end + 1, version_end - path_end - 1);

    size_t qpos = path_query.find('?');
    if (qpos != std::string::npos) {
        hr.path = path_query.substr(0, qpos);
        hr.query = path_query.substr(qpos + 1);
    } else {
        hr.path = path_query;
        hr.query = "";
    }

    size_t header_start = version_end + 2;
    size_t body_start = req.find("\r\n\r\n", header_start);
    std::string header_str = req.substr(header_start, body_start - header_start);
    parse_headers(header_str, hr.headers);

    if (body_start != std::string::npos) {
        hr.body = req.substr(body_start + 4);
    }
    return hr;
}

// HTTP Response helpers
void send_response(int client_sock, const std::string& status, const std::string& content_type, const std::string& body) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << status << "\r\n";
    oss << "Content-Type: " << content_type << "\r\n";
    oss << "Content-Length: " << body.size() << "\r\n";
    oss << "Connection: close\r\n";
    oss << "\r\n";
    oss << body;
    std::string res = oss.str();
    send(client_sock, res.c_str(), res.size(), 0);
}

void handle_data_get(int client_sock, const std::map<std::string, std::string>& query_params) {
    std::string xml = generate_device_data_xml(query_params);
    send_response(client_sock, "200 OK", "application/xml", xml);
}

void handle_commands_post(int client_sock, const std::string& body) {
    // Parse body for action (expect: {"action": "something"})
    std::string action;
    size_t action_pos = body.find("\"action\"");
    if (action_pos != std::string::npos) {
        // crude extraction
        size_t colon = body.find(':', action_pos);
        size_t quote1 = body.find('\"', colon);
        size_t quote2 = body.find('\"', quote1 + 1);
        if (quote1 != std::string::npos && quote2 != std::string::npos && quote2 > quote1)
            action = body.substr(quote1 + 1, quote2 - quote1 - 1);
    }
    bool accepted = !action.empty();
    std::string resp = generate_command_response_json(action, accepted);
    send_response(client_sock, accepted ? "200 OK" : "400 Bad Request", "application/json", resp);
}

void handle_request(int client_sock) {
    char buffer[BUFFER_SIZE];
    ssize_t bytes = recv(client_sock, buffer, sizeof(buffer) - 1, 0);
    if (bytes <= 0) {
        close(client_sock);
        return;
    }
    buffer[bytes] = '\0';
    std::string request(buffer);

    HttpRequest hr = parse_http_request(request);

    if (hr.method == "GET" && hr.path == "/data") {
        handle_data_get(client_sock, parse_query(hr.query));
    } else if (hr.method == "POST" && hr.path == "/commands") {
        handle_commands_post(client_sock, hr.body);
    } else {
        send_response(client_sock, "404 Not Found", "text/plain", "404 Not Found\n");
    }
    close(client_sock);
}

int main() {
    std::string server_host = getenv_or_default("SERVER_HOST", "0.0.0.0");
    int server_port = std::stoi(getenv_or_default("SERVER_PORT", "8080"));

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == -1) {
        std::cerr << "Failed to create socket\n";
        return 1;
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(server_port);
    addr.sin_addr.s_addr = INADDR_ANY;
    if (server_host != "0.0.0.0") {
        addr.sin_addr.s_addr = inet_addr(server_host.c_str());
    }

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Bind failed\n";
        close(server_fd);
        return 1;
    }

    if (listen(server_fd, 10) < 0) {
        std::cerr << "Listen failed\n";
        close(server_fd);
        return 1;
    }

    std::cout << "HTTP server started at " << server_host << ":" << server_port << std::endl;

    while (true) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client_sock < 0) {
            continue;
        }
        std::thread(handle_request, client_sock).detach();
    }

    close(server_fd);
    return 0;
}