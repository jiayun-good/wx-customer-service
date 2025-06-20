#include <iostream>
#include <cstdlib>
#include <string>
#include <sstream>
#include <vector>
#include <map>
#include <cstring>
#include <thread>
#include <algorithm>
#include <cstdio>
#include <ctime>
#include <unistd.h>
#include <netinet/in.h>
#include <sys/socket.h>

#define BUFFER_SIZE 8192

// Helper: Parse query string into a map
std::map<std::string, std::string> parse_query_string(const std::string &query) {
    std::map<std::string, std::string> params;
    std::string::size_type start = 0, end;
    while ((end = query.find('&', start)) != std::string::npos) {
        std::string kv = query.substr(start, end - start);
        std::string::size_type eq = kv.find('=');
        if (eq != std::string::npos) {
            params[kv.substr(0, eq)] = kv.substr(eq + 1);
        }
        start = end + 1;
    }
    std::string kv = query.substr(start);
    std::string::size_type eq = kv.find('=');
    if (eq != std::string::npos) {
        params[kv.substr(0, eq)] = kv.substr(eq + 1);
    }
    return params;
}

// Helper: URL decode
std::string url_decode(const std::string &src) {
    std::string ret;
    char ch;
    int i, ii;
    for (i = 0; i < src.length(); i++) {
        if (src[i] == '%') {
            sscanf(src.substr(i + 1, 2).c_str(), "%x", &ii);
            ch = static_cast<char>(ii);
            ret += ch;
            i = i + 2;
        } else if (src[i] == '+') {
            ret += ' ';
        } else {
            ret += src[i];
        }
    }
    return ret;
}

// Simulate fetching device data in XML (stub)
std::string get_device_data_xml(const std::map<std::string, std::string>& params) {
    // Simulate parameters: filter, limit, offset
    std::string filter = params.count("filter") ? url_decode(params.at("filter")) : "all";
    int limit = params.count("limit") ? std::stoi(params.at("limit")) : 10;
    int offset = params.count("offset") ? std::stoi(params.at("offset")) : 0;

    std::ostringstream oss;
    oss << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    oss << "<DeviceData>\n";
    for (int i = offset; i < offset + limit; ++i) {
        oss << "  <DataPoint>\n";
        oss << "    <ID>" << i << "</ID>\n";
        oss << "    <Value>" << (100 + i) << "</Value>\n";
        oss << "    <Filter>" << filter << "</Filter>\n";
        oss << "  </DataPoint>\n";
    }
    oss << "</DeviceData>\n";
    return oss.str();
}

// Simulate command execution (stub)
std::string handle_device_command(const std::string& cmd_payload) {
    // Returns a fixed JSON reply for demonstration
    std::ostringstream oss;
    std::time_t t = std::time(nullptr);

    oss << "{";
    oss << "\"timestamp\":" << t << ",";
    oss << "\"status\":\"accepted\",";
    oss << "\"result\":\"Command executed\",";
    oss << "\"payload\":\"" << cmd_payload << "\"";
    oss << "}";
    return oss.str();
}

// Parse HTTP request line and headers
void parse_http_request(const std::string &request, std::string &method, std::string &path, std::string &query, std::map<std::string, std::string> &headers, std::string &body) {
    std::istringstream iss(request);
    std::string line;
    bool first_line = true;
    bool in_headers = true;
    size_t content_length = 0;

    while (std::getline(iss, line)) {
        // Remove trailing \r
        if (!line.empty() && line.back() == '\r') line.pop_back();

        if (first_line) {
            first_line = false;
            std::istringstream lineiss(line);
            lineiss >> method;
            std::string uri;
            lineiss >> uri;
            auto qpos = uri.find('?');
            if (qpos != std::string::npos) {
                path = uri.substr(0, qpos);
                query = uri.substr(qpos + 1);
            } else {
                path = uri;
                query = "";
            }
            continue;
        }

        if (in_headers) {
            if (line.empty()) {
                in_headers = false;
                continue;
            }
            size_t pos = line.find(':');
            if (pos != std::string::npos) {
                std::string key = line.substr(0, pos);
                std::string value = line.substr(pos + 1);
                // Trim spaces
                key.erase(key.find_last_not_of(' ') + 1);
                value.erase(0, value.find_first_not_of(' '));
                headers[key] = value;
                if (key == "Content-Length") {
                    content_length = std::stoul(value);
                }
            }
        } else {
            // Everything after headers is the body
            std::ostringstream bodyoss;
            bodyoss << line << "\n";
            while (std::getline(iss, line)) {
                if (!line.empty() && line.back() == '\r') line.pop_back();
                bodyoss << line << "\n";
            }
            body = bodyoss.str();
            if (body.size() > content_length)
                body = body.substr(0, content_length);
            break;
        }
    }
}

// Send HTTP response
void send_http_response(int client_sock, const std::string &status, const std::string &content_type, const std::string &body) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << status << "\r\n";
    oss << "Content-Type: " << content_type << "\r\n";
    oss << "Content-Length: " << body.size() << "\r\n";
    oss << "Access-Control-Allow-Origin: *\r\n";
    oss << "\r\n";
    oss << body;
    std::string response = oss.str();
    send(client_sock, response.c_str(), response.size(), 0);
}

// Worker for each incoming connection
void handle_client(int client_sock) {
    char buffer[BUFFER_SIZE];
    ssize_t received = recv(client_sock, buffer, sizeof(buffer) - 1, 0);
    if (received <= 0) {
        close(client_sock);
        return;
    }
    buffer[received] = 0;
    std::string request(buffer);

    std::string method, path, query, body;
    std::map<std::string, std::string> headers;
    parse_http_request(request, method, path, query, headers, body);

    // Route handling
    if (method == "GET" && path == "/data") {
        auto params = parse_query_string(query);
        std::string xml = get_device_data_xml(params);
        send_http_response(client_sock, "200 OK", "application/xml", xml);
    } else if (method == "POST" && path == "/commands") {
        // Accept both XML and JSON, but reply in JSON
        std::string result_json = handle_device_command(body);
        send_http_response(client_sock, "200 OK", "application/json", result_json);
    } else {
        std::string notfound = "{\"error\":\"Not found\"}";
        send_http_response(client_sock, "404 Not Found", "application/json", notfound);
    }

    close(client_sock);
}

int main() {
    // Get config from environment
    const char* env_host = std::getenv("DEVICE_SHIFU_HTTP_SERVER_HOST");
    const char* env_port = std::getenv("DEVICE_SHIFU_HTTP_SERVER_PORT");

    std::string host = env_host ? env_host : "0.0.0.0";
    int port = env_port ? std::stoi(env_port) : 8080;

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == -1) {
        perror("socket failed");
        return 1;
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in address;
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = host == "0.0.0.0" ? INADDR_ANY : inet_addr(host.c_str());
    address.sin_port = htons(port);

    if (bind(server_fd, (struct sockaddr*)&address, sizeof(address)) < 0) {
        perror("bind failed");
        close(server_fd);
        return 1;
    }

    if (listen(server_fd, 10) < 0) {
        perror("listen failed");
        close(server_fd);
        return 1;
    }

    std::cout << "DeviceShifu HTTP driver running at " << host << ":" << port << std::endl;

    while (true) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client_sock < 0) {
            perror("accept failed");
            continue;
        }
        std::thread(handle_client, client_sock).detach();
    }

    close(server_fd);
    return 0;
}