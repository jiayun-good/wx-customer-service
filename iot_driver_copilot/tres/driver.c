#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <microhttpd.h>

#define DEFAULT_HTTP_PORT "8080"
#define RESPONSE_BUFFER_SIZE 8192
#define XML_DATA_FILE "device_data.xml" // fallback, not used in this example

// Helper function to get environment variables with default fallback
const char* get_env(const char* name, const char* def) {
    const char* val = getenv(name);
    return val ? val : def;
}

// Dummy XML data representing device data points
const char* device_xml_data =
    "<deviceData>"
        "<dataPoint><name>temperature</name><value>23.5</value></dataPoint>"
        "<dataPoint><name>humidity</name><value>44</value></dataPoint>"
        "<dataPoint><name>status</name><value>active</value></dataPoint>"
    "</deviceData>";

// Very minimal XML to JSON converter (for the fixed dummy XML above)
void xml_to_json(const char* xml, char* json, size_t json_size) {
    // This is a simple and static translation for the given XML
    snprintf(json, json_size,
        "{"
            "\"dataPoints\": ["
                "{\"name\": \"temperature\", \"value\": \"23.5\"},"
                "{\"name\": \"humidity\", \"value\": \"44\"},"
                "{\"name\": \"status\", \"value\": \"active\"}"
            "]"
        "}"
    );
}

// Parse query parameters for 'filter' and 'page'
void parse_query_params(const char* url, char* filter, size_t filter_size, char* page, size_t page_size) {
    filter[0] = '\0';
    page[0] = '\0';

    const char* q = strchr(url, '?');
    if (!q) return;
    q++; // skip '?'

    char* query = strdup(q);
    char* token = strtok(query, "&");
    while (token) {
        if (strncmp(token, "filter=", 7) == 0) {
            strncpy(filter, token + 7, filter_size-1);
            filter[filter_size-1] = '\0';
        } else if (strncmp(token, "page=", 5) == 0) {
            strncpy(page, token + 5, page_size-1);
            page[page_size-1] = '\0';
        }
        token = strtok(NULL, "&");
    }
    free(query);
}

// Handler for /data endpoint
int handle_data(struct MHD_Connection* connection, const char* url) {
    char filter[128], page[32];
    parse_query_params(url, filter, sizeof(filter), page, sizeof(page));

    char json[RESPONSE_BUFFER_SIZE];
    xml_to_json(device_xml_data, json, sizeof(json));

    // Filtering and pagination not implemented (static data)
    struct MHD_Response* response = MHD_create_response_from_buffer(strlen(json),
                                                                   (void*)json,
                                                                   MHD_RESPMEM_MUST_COPY);
    if (!response) return MHD_NO;

    MHD_add_response_header(response, "Content-Type", "application/json");
    int ret = MHD_queue_response(connection, MHD_HTTP_OK, response);
    MHD_destroy_response(response);
    return ret;
}

// Main HTTP request handler
int http_request_handler(void* cls,
                        struct MHD_Connection* connection,
                        const char* url,
                        const char* method,
                        const char* version,
                        const char* upload_data,
                        size_t* upload_data_size,
                        void** con_cls) {
    (void)cls; (void)version; (void)upload_data; (void)upload_data_size; (void)con_cls;

    if (strcmp(method, "GET") == 0 && strncmp(url, "/data", 5) == 0) {
        return handle_data(connection, url);
    }

    const char* notfound = "{\"error\": \"Not found\"}";
    struct MHD_Response* response = MHD_create_response_from_buffer(strlen(notfound),
                                                                   (void*)notfound,
                                                                   MHD_RESPMEM_PERSISTENT);
    if (!response) return MHD_NO;
    MHD_add_response_header(response, "Content-Type", "application/json");
    int ret = MHD_queue_response(connection, MHD_HTTP_NOT_FOUND, response);
    MHD_destroy_response(response);
    return ret;
}

int main() {
    const char* http_port_env = get_env("HTTP_PORT", DEFAULT_HTTP_PORT);
    unsigned short http_port = (unsigned short)atoi(http_port_env);
    if (http_port == 0) http_port = (unsigned short)atoi(DEFAULT_HTTP_PORT);

    struct MHD_Daemon* daemon;
    daemon = MHD_start_daemon(MHD_USE_SELECT_INTERNALLY, http_port,
                              NULL, NULL,
                              &http_request_handler, NULL,
                              MHD_OPTION_END);
    if (NULL == daemon) {
        fprintf(stderr, "Failed to start HTTP server on port %d\n", http_port);
        return 1;
    }

    printf("DeviceShifu HTTP server running on port %d ...\n", http_port);
    fflush(stdout);

    // Run forever
    while (1) {
        sleep(60);
    }

    MHD_stop_daemon(daemon);
    return 0;
}