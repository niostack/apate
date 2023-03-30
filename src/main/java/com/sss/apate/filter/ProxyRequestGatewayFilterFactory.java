package com.sss.apate.filter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sss.apate.util.AESUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.*;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 代理网关过滤器
 *
 * @author sss
 */
@Component
@Slf4j
public class ProxyRequestGatewayFilterFactory extends AbstractGatewayFilterFactory<ProxyRequestGatewayFilterFactory.Config> {


    public static final String CACHE_PROXY_REQUEST_BODY_OBJECT_KEY = "CPRBOK";

    private final List<HttpMessageReader<?>> messageReaders;

    private final ObjectMapper mapper;

    public ProxyRequestGatewayFilterFactory() {
        this(HandlerStrategies.withDefaults().messageReaders());
    }

    public ProxyRequestGatewayFilterFactory(List<HttpMessageReader<?>> messageReaders) {
        super(Config.class);
        this.messageReaders = messageReaders;
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return ((exchange, chain) -> {
            if (config.serviceHost == null || config.serviceHost.isEmpty()) {
                return Mono.empty();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.putAll(exchange.getRequest().getHeaders());
            headers.remove(HttpHeaders.CONTENT_LENGTH);
            CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);

            Mono<String> modifiedBody = parseAndModifyBody(exchange, config);
            BodyInserter<Mono<String>, ReactiveHttpOutputMessage> bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);

            return bodyInserter.insert(outputMessage, new BodyInserterContext())
                    .then(Mono.defer(() -> {
                        ProxyConfig proxyConfig = exchange.getAttribute(CACHE_PROXY_REQUEST_BODY_OBJECT_KEY);
                        if (proxyConfig == null || proxyConfig.getService() == null
                                || proxyConfig.getMethod() == null || proxyConfig.getContentType() == null) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
                        }

                        ServerHttpRequest decorator = decorate(config, exchange, headers, outputMessage);

                        return chain.filter(exchange.mutate().request(decorator).build());
                    }));
        });
    }

    /**
     * 解析并修改 body
     *
     * @param exchange
     * @param config
     * @return
     */
    private Mono<String> parseAndModifyBody(ServerWebExchange exchange, Config config) {
        return ServerRequest.create(exchange, messageReaders).bodyToMono(String.class)
                .flatMap(originalBody -> {
                    ProxyConfig proxyConfig = parseBody(originalBody, config);
                    if (proxyConfig == null) {
                        return Mono.empty();
                    }

                    exchange.getAttributes().put(CACHE_PROXY_REQUEST_BODY_OBJECT_KEY, proxyConfig);

                    // 如果是 post 则将参数写入表单
                    if (HttpMethod.POST.name().equals(proxyConfig.getMethod())) {
                        String params = buildRequestParams(proxyConfig);
                        return params == null ? Mono.empty() : Mono.just(params);
                    }

                    return Mono.empty();
                });
    }

    /**
     * 解析body
     *
     * @param originalBody
     * @param config
     * @return
     */
    private ProxyConfig parseBody(String originalBody, Config config) {
        try {
            String body = originalBody;
            if (config.encryptEnable) {
                body = AESUtil.decrypt(originalBody, config.getEncryptKey());
            }
            return mapper.readValue(body, ProxyConfig.class);
        } catch (Exception e) {
            log.error("parse body error. originalBody: {}, exception: {}", originalBody, e);
        }

        return null;
    }

    /**
     * @param exchange
     * @param headers
     * @param outputMessage
     * @return
     */
    private ServerHttpRequest decorate(Config config, ServerWebExchange exchange,
                                       HttpHeaders headers, CachedBodyOutputMessage outputMessage) {
        ProxyConfig proxyConfig = exchange.getAttribute(CACHE_PROXY_REQUEST_BODY_OBJECT_KEY);

        // 构造新的 URI
        URI uri = buildUri(exchange, config, proxyConfig);
        modifiyRoute(exchange, uri, proxyConfig);

        return new ServerHttpRequestDecorator(exchange.getRequest()) {

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public HttpMethod getMethod() {
                HttpMethod method = HttpMethod.resolve(proxyConfig.getMethod());
                return method == null ? HttpMethod.POST : method;
            }

            @Override
            public String getMethodValue() {
                return getMethod().name();
            }

            @Override
            public HttpHeaders getHeaders() {
                long contentLength = headers.getContentLength();
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.putAll(super.getHeaders());
                httpHeaders.setContentLength(contentLength);
                if (contentLength <= 0) {
                    httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
                }

                if (proxyConfig.getHeaders() != null) {
                    proxyConfig.getHeaders().forEach(httpHeaders::set);
                }

                httpHeaders.set(HttpHeaders.CONTENT_TYPE, proxyConfig.getContentType());

                return httpHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return outputMessage.getBody();
            }
        };
    }

    /**
     * 构建代理请求 URI
     *
     * @param exchange
     * @param config
     * @param proxyConfig
     * @return
     */
    private URI buildUri(ServerWebExchange exchange, Config config, ProxyConfig proxyConfig) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpRequest(exchange.getRequest())
                .uri(URI.create(config.serviceHost.get(proxyConfig.getService())))
                .port(proxyConfig.getPort());

        String path = proxyConfig.getPath();
        int position = path.indexOf("?");
        if (position == -1) {
            uriBuilder.replacePath(path);
        } else {
            uriBuilder.replacePath(path.substring(0, position));
            uriBuilder.query(path.substring(position + 1));
        }

        if (HttpMethod.GET.name().equals(proxyConfig.getMethod())) {
            String query = buildRequestParams(proxyConfig);
            uriBuilder.query(query);
        }

        return uriBuilder.build().toUri();
    }

    /**
     * 构建请求参数
     *
     * @param proxyConfig
     * @return
     */
    private String buildRequestParams(ProxyConfig proxyConfig) {
        Map<String, Object> params = proxyConfig.getParams();
        if (params == null || params.isEmpty()) {
            return null;
        }

        String method = proxyConfig.getMethod();
        String contentType = proxyConfig.getContentType();

        if (HttpMethod.POST.name().equals(method) && MediaType.APPLICATION_JSON_VALUE.equals(contentType)) {
            try {
                return mapper.writeValueAsString(params);
            } catch (Exception e) {
                log.warn("json error.");
            }
        } else {
            boolean needEncode = MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(contentType);

            StringBuilder sb = new StringBuilder();
            for (String key : params.keySet()) {
                Object value = params.get(key);

                if (needEncode && value instanceof String) {
                    try {
                        value = URLEncoder.encode((String) value, StandardCharsets.UTF_8.name());
                    } catch (UnsupportedEncodingException e) {
                        log.error("Params encode error. key:{}, value:{}, e:{}", key, value, e);
                    }
                }

                sb.append(key).append("=").append(value).append("&");
            }

            String query = sb.toString();
            if (query.charAt(query.length() - 1) == '&') {
                query = query.substring(0, query.length() - 1);
            }

            return query;
        }

        return null;
    }

    /**
     * 修改 Route
     *
     * @param exchange
     * @param uri
     * @param proxyConfig
     */
    private void modifiyRoute(ServerWebExchange exchange, URI uri, ProxyConfig proxyConfig) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Route newRoute = Route.async()
                .id(proxyConfig.getService())
                .asyncPredicate(route.getPredicate())
                .filters(route.getFilters())
                .uri(uri)
                .build();

        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, newRoute);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, uri);
    }

    /**
     * 代理配置
     */
    public static class ProxyConfig {
        private String service;
        private String method = HttpMethod.POST.name();
        private String path;
        private Integer port = 80;
        private String contentType;
        private Map<String, String> headers = new HashMap<>();
        private Map<String, Object> params = new HashMap<>();

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getContentType() {
            return contentType != null ? contentType : headers.get(HttpHeaders.CONTENT_TYPE);
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params;
        }
    }

    /**
     * 过滤器配置
     */
    public static class Config {
        private Boolean encryptEnable;
        private String encrypt;
        private String encryptKey;
        private Map<String, String> serviceHost;

        public Boolean getEncryptEnable() {
            return encryptEnable;
        }

        public void setEncryptEnable(Boolean encryptEnable) {
            this.encryptEnable = encryptEnable;
        }

        public String getEncrypt() {
            return encrypt;
        }

        public void setEncrypt(String encrypt) {
            this.encrypt = encrypt;
        }

        public String getEncryptKey() {
            return encryptKey;
        }

        public void setEncryptKey(String encryptKey) {
            this.encryptKey = encryptKey;
        }

        public Map<String, String> getServiceHost() {
            return serviceHost;
        }

        public void setServiceHost(Map<String, String> serviceHost) {
            this.serviceHost = serviceHost;
        }
    }
}
