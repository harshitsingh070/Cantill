package com.corvo.procurement.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiClient {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String token;
    private final int retries;

    public AiClient(@Value("${corvo.ai.base-url}") String baseUrl,
                    @Value("${corvo.ai.token:}") String token,
                    @Value("${corvo.ai.timeout-ms:4000}") long timeoutMs,
                    @Value("${corvo.ai.retries:1}") int retries,
                    ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.retries = Math.max(0, retries);
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
    }

    public Map<String, Object> post(String path, Map<String, Object> body) {
        HttpHeaders headers = headers(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return execute(path, entity);
    }

    public Map<String, Object> upload(String path, byte[] bytes, String filename,
                                      Map<String, String> fields) {
        HttpHeaders headers = headers(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        if (fields != null) {
            fields.forEach(parts::add);
        }
        parts.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename == null ? "upload" : filename;
            }
        });
        return execute(path, new HttpEntity<>(parts, headers));
    }

    private Map<String, Object> execute(String path, HttpEntity<?> entity) {
        String url = baseUrl + (path.startsWith("/") ? path : "/" + path);
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
                String raw = response.getBody();
                if (raw == null || raw.isBlank()) {
                    return new LinkedHashMap<>();
                }
                return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
                });
            } catch (RestClientException e) {
                last = e;
            } catch (Exception e) {
                throw new AiUnavailableException("AI service returned an invalid response", e);
            }
        }
        throw new AiUnavailableException("AI service is unavailable at " + baseUrl
                + "; use the manual policy editor or try again after the AI service wakes", last);
    }

    private HttpHeaders headers(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}
