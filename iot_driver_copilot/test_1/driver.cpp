#include <cstdlib>
#include <iostream>
#include <string>
#include <sstream>
#include <map>
#include <vector>
#include <algorithm>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <netinet/in.h>
#include <sys/socket.h>

// Utility for parsing query parameters
std::map<std::string, std::string> parse_query_params(const std::string& query) {
    std::map<std::string, std::string> params;
    std::istringstream ss(query);
    std::string item;
    while (std::getline(ss, item, '&')) {
        auto eq = item.find('=');
        if (eq != std::string::npos) {
            params[item.substr(0, eq)] = item.substr(eq + 1);
        }
    }
    return params;
}

// Simple HTTP Response helpers
void send_http_response(int client, const std::string& status, const std::string& content_type, const std::string& body) {
    std::ostringstream resp;
    resp << "HTTP/1.1 " << status << "\r\n";
    resp << "Content-Type: " << content_type << "\r\n";
    resp << "Content-Length: " << body.length() << "\r\n";
    resp << "Access-Control-Allow-Origin: *\r\n";
    resp << "Connection: close\r\n\r\n";
    resp << body;
    std::string resp_str = resp.str();
    send(client, resp_str.c_str(), resp_str.length(), 0);
}

void send_http_json(int client, const std::string& status, const std::string& body) {
    send_http_response(client, status, "application/json", body);
}

void send_http_xml(int client, const std::string& status, const std::string& body) {
    send_http_response(client, status, "application/xml", body);
}

// Mock function to retrieve device data as XML (simulate real device)
std::string get_device_data_xml(const std::map<std::string, std::string>& params) {
    // Simulated data
    std::ostringstream xml;
    xml << "<?xml version=\"1.0\"?>\n";
    xml << "<DeviceData>\n";
    xml << "  <DeviceName>test1</DeviceName>\n";
    xml << "  <DeviceModel>dsa</DeviceModel>\n";
    xml << "  <Manufacturer>adsads</Manufacturer>\n";
    xml << "  <Type>ads</Type>\n";
    xml << "  <DataPoints>adsads</DataPoints>\n";
    xml << "  <Status>OK</Status>\n";
    // Optionally filter, limit, offset (mocked)
    if (params.count("filter")) {
        xml << "  <Filter>" << params.at("filter") << "</Filter>\n";
    }
    if (params.count("limit")) {
        xml << "  <Limit>" << params.at("limit") << "</Limit>\n";
    }
    if (params.count("offset")) {
        xml << "  <Offset>" << params.at("offset") << "</Offset>\n";
    }
    xml << "</DeviceData>\n";
    return xml.str();
}

// Mock function to handle device command
std::string handle_device_command(const std::string& payload) {
    // In reality, you would parse the payload and execute a device command.
    // Here, we just echo back a confirmation with the received action.
    std::string action;
    auto pos = payload.find("action");
    if (pos != std::string::npos) {
        // Very naive extraction
        size_t start = payload.find(':', pos);
        size_t end = payload.find_first_of(",}", start);
        if (start != std::string::npos && end != std::string::npos) {
            action = payload.substr(start + 1, end - start - 1);
            // Remove quotes and whitespace
            action.erase(std::remove_if(action.begin(), action.end(), [](unsigned char c){ return c=='\"'||isspace(c); }), action.end());
        }
    }
    std::ostringstream json;
    json << "{";
    json << "\"status\":\"accepted\",";
    json << "\"executed\":true,";
    json << "\"action\":\"" << (action.empty() ? "unknown" : action) << "\"";
    json << "}";
    return json.str();
}

// HTTP Server implementation
class HttpServer {
public:
    HttpServer(const std::string& host, int port)
        : host_(host), port_(port), server_fd_(-1), stop_(false) {}

    ~HttpServer() {
        stop();
    }

    void start() {
        struct sockaddr_in address;
        int opt = 1;

        if ((server_fd_ = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
            perror("socket failed");
            exit(EXIT_FAILURE);
        }

        if (setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR | SO_REUSEPORT, &opt, sizeof(opt))) {
            perror("setsockopt");
            exit(EXIT_FAILURE);
        }
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = INADDR_ANY;
        address.sin_port = htons(port_);

        if (bind(server_fd_, (struct sockaddr *)&address, sizeof(address)) < 0) {
            perror("bind failed");
            exit(EXIT_FAILURE);
        }
        if (listen(server_fd_, 16) < 0) {
            perror("listen");
            exit(EXIT_FAILURE);
        }

        while (!stop_) {
            int addrlen = sizeof(address);
            int new_socket = accept(server_fd_, (struct sockaddr *)&address, (socklen_t*)&addrlen);
            if (new_socket < 0) {
                if (stop_) break;
                perror("accept");
                continue;
            }
            std::thread(&HttpServer::handle_client, this, new_socket).detach();
        }
    }

    void stop() {
        stop_ = true;
        if (server_fd_ != -1) {
            close(server_fd_);
            server_fd_ = -1;
        }
    }

private:
    std::string host_;
    int port_;
    int server_fd_;
    bool stop_;

    void handle_client(int client) {
        constexpr size_t buffer_size = 8192;
        char buffer[buffer_size];
        int valread = read(client, buffer, buffer_size - 1);
        if (valread <= 0) {
            close(client);
            return;
        }
        buffer[valread] = 0; // null-terminate

        std::istringstream request(buffer);
        std::string request_line;
        std::getline(request, request_line);
        if (request_line.back() == '\r') request_line.pop_back();

        std::string method, path, version;
        std::istringstream rl(request_line);
        rl >> method >> path >> version;

        // Read headers
        std::string header;
        std::map<std::string, std::string> headers;
        size_t content_length = 0;
        while (std::getline(request, header) && header != "\r" && !header.empty()) {
            if (header.back() == '\r') header.pop_back();
            auto sep = header.find(':');
            if (sep != std::string::npos) {
                std::string key = header.substr(0, sep);
                std::string val = header.substr(sep + 1);
                val.erase(0, val.find_first_not_of(" \t"));
                headers[key] = val;
                if (key == "Content-Length") {
                    content_length = std::stoi(val);
                }
            }
        }

        std::string query, route;
        auto qm = path.find('?');
        if (qm != std::string::npos) {
            route = path.substr(0, qm);
            query = path.substr(qm + 1);
        } else {
            route = path;
        }

        // Read POST body if needed
        std::string body;
        if (method == "POST" && content_length > 0) {
            body.resize(content_length);
            request.read(&body[0], content_length);
            if (request.gcount() < (int)content_length) {
                // If not all read, try read directly from socket
                int remain = content_length - request.gcount();
                int r = read(client, &body[request.gcount()], remain);
                if (r > 0) {
                    // ok
                }
            }
        }

        if (method == "GET" && route == "/data") {
            auto params = parse_query_params(query);
            std::string xml = get_device_data_xml(params);
            send_http_xml(client, "200 OK", xml);
        } else if (method == "POST" && route == "/commands") {
            if (body.empty()) {
                send_http_json(client, "400 Bad Request", "{\"error\":\"Missing payload\"}");
            } else {
                std::string result = handle_device_command(body);
                send_http_json(client, "200 OK", result);
            }
        } else {
            send_http_json(client, "404 Not Found", "{\"error\":\"Not found\"}");
        }

        close(client);
    }
};

int main() {
    // Configuration from environment variables
    const char* env_host = std::getenv("HTTP_SERVER_HOST");
    const char* env_port = std::getenv("HTTP_SERVER_PORT");

    std::string server_host = env_host ? env_host : "0.0.0.0";
    int server_port = env_port ? std::atoi(env_port) : 8080;

    HttpServer server(server_host, server_port);
    std::cout << "DeviceShifu HTTP Driver starting at " << server_host << ":" << server_port << std::endl;
    server.start();

    return 0;
}