package com.sss.apate.filter;

import com.sss.apate.util.AESUtil;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.function.Function.identity;

/**
 * 返回代理类
 *
 * @author sss
 */
@Component
@Slf4j
public class ProxyResponseGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ProxyResponseGatewayFilterFactory.Config> {


    private Map<String, MessageBodyDecoder> messageBodyDecoders;

    private Map<String, MessageBodyEncoder> messageBodyEncoders;

    private final List<HttpMessageReader<?>> messageReaders;

    public ProxyResponseGatewayFilterFactory(
            Set<MessageBodyDecoder> messageBodyDecoders,
            Set<MessageBodyEncoder> messageBodyEncoders) {
        super(Config.class);
        this.messageReaders = HandlerStrategies.withDefaults().messageReaders();
        this.messageBodyDecoders = messageBodyDecoders.stream()
                .collect(Collectors.toMap(MessageBodyDecoder::encodingType, identity()));
        this.messageBodyEncoders = messageBodyEncoders.stream()
                .collect(Collectors.toMap(MessageBodyEncoder::encodingType, identity()));
    }

    @Override
    public GatewayFilter apply(Config config) {
        ProxyResponseGatewayFilter gatewayFilter = new ProxyResponseGatewayFilter(config);
        gatewayFilter.setFactory(this);

        return gatewayFilter;
    }


    public class ProxyResponseGatewayFilter implements GatewayFilter, Ordered {

        private final Config config;

        private GatewayFilterFactory<Config> gatewayFilterFactory;

        public ProxyResponseGatewayFilter(Config config) {
            this(config, null);
        }

        @Deprecated
        public ProxyResponseGatewayFilter(Config config, @Nullable ServerCodecConfigurer codecConfigurer) {
            this.config = config;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return chain.filter(exchange.mutate()
                    .response(new ProxyServerHttpResponse(exchange, config)).build());
        }

        @SuppressWarnings("unchecked")
        @Deprecated
        ServerHttpResponse decorate(ServerWebExchange exchange) {
            return new ProxyServerHttpResponse(exchange, config);
        }

        @Override
        public int getOrder() {
            return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
        }

        public void setFactory(GatewayFilterFactory<Config> gatewayFilterFactory) {
            this.gatewayFilterFactory = gatewayFilterFactory;
        }
    }

    protected class ProxyServerHttpResponse extends ServerHttpResponseDecorator {

        private final ServerWebExchange exchange;

        private final Config config;

        public ProxyServerHttpResponse(ServerWebExchange exchange, Config config) {
            super(exchange.getResponse());
            this.exchange = exchange;
            this.config = config;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);
            ClientResponse clientResponse = prepareClientResponse(body, httpHeaders);

            Mono modifiedBody = extractBody(exchange, clientResponse, String.class).flatMap(originalBody -> {
                try {
                    String resBody=originalBody;
                    if(config.encryptEnable) {
                        resBody = AESUtil.encrypt(originalBody, config.encryptKey);
                    }
                    log.debug("Body encrypt success.originalBody:{}, modifiedBody:{}", originalBody, resBody);

                    return Mono.just(resBody);
                } catch (Exception e) {
                    log.error("Body encrypt error.originalBody:{}", originalBody);
                }

                return Mono.empty();
            });

            BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);
            CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, exchange.getResponse().getHeaders());


            return bodyInserter.insert(outputMessage, new BodyInserterContext())
                    .then(Mono.defer(() -> {
                        Mono<DataBuffer> messageBody = writeBody(getDelegate(), outputMessage, String.class);
                        HttpHeaders headers = getDelegate().getHeaders();
                        if (!headers.containsKey(HttpHeaders.TRANSFER_ENCODING)
                                || headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
                            messageBody = messageBody.doOnNext(data -> headers
                                    .setContentLength(data.readableByteCount()));
                        }
                        // TODO: fail if isStreamingMediaType?
                        return getDelegate().writeWith(messageBody);
                    }));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMapSequential(p -> p));
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders httpHeaders = super.getHeaders();
            httpHeaders.add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);

            return httpHeaders;
        }

        private ClientResponse prepareClientResponse(Publisher<? extends DataBuffer> body, HttpHeaders httpHeaders) {
            ClientResponse.Builder builder;
            builder = ClientResponse.create(exchange.getResponse().getStatusCode(), messageReaders);
            return builder.headers(headers -> headers.putAll(httpHeaders)).body(Flux.from(body)).build();
        }

        private <T> Mono<T> extractBody(ServerWebExchange exchange, ClientResponse clientResponse, Class<T> inClass) {
            // if inClass is byte[] then just return body, otherwise check if
            // decoding required
            // if (byte[].class.isAssignableFrom(inClass)) {
            //     return clientResponse.bodyToMono(inClass);
            // }

            List<String> encodingHeaders = exchange.getResponse().getHeaders()
                    .getOrEmpty(HttpHeaders.CONTENT_ENCODING);
            for (String encoding : encodingHeaders) {
                MessageBodyDecoder decoder = messageBodyDecoders.get(encoding);
                if (decoder != null) {
                    return clientResponse.bodyToMono(byte[].class)
                            .publishOn(Schedulers.parallel()).map(decoder::decode)
                            .map(bytes -> exchange.getResponse().bufferFactory().wrap(bytes))
                            .map(buffer -> prepareClientResponse(Mono.just(buffer),
                                    exchange.getResponse().getHeaders()))
                            .flatMap(response -> response.bodyToMono(inClass));
                }
            }

            return (Mono<T>) clientResponse.bodyToMono(String.class);
        }

        private Mono<DataBuffer> writeBody(ServerHttpResponse httpResponse, CachedBodyOutputMessage message, Class<?> outClass) {
            Mono<DataBuffer> response = DataBufferUtils.join(message.getBody());
            if (byte[].class.isAssignableFrom(outClass)) {
                return response;
            }

            List<String> encodingHeaders = httpResponse.getHeaders()
                    .getOrEmpty(HttpHeaders.CONTENT_ENCODING);
            for (String encoding : encodingHeaders) {
                MessageBodyEncoder encoder = messageBodyEncoders.get(encoding);
                if (encoder != null) {
                    DataBufferFactory dataBufferFactory = httpResponse.bufferFactory();
                    response = response.publishOn(Schedulers.parallel())
                            .map(encoder::encode).map(dataBufferFactory::wrap);
                    break;
                }
            }

            return response;

        }
    }

    /**
     * 过滤器配置
     */
    public static class Config {
        private Boolean encryptEnable;
        private String encrypt;
        private String encryptKey;

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
    }

    public Map<String, MessageBodyDecoder> getMessageBodyDecoders() {
        return messageBodyDecoders;
    }

    public void setMessageBodyDecoders(Map<String, MessageBodyDecoder> messageBodyDecoders) {
        this.messageBodyDecoders = messageBodyDecoders;
    }

    public Map<String, MessageBodyEncoder> getMessageBodyEncoders() {
        return messageBodyEncoders;
    }

    public void setMessageBodyEncoders(Map<String, MessageBodyEncoder> messageBodyEncoders) {
        this.messageBodyEncoders = messageBodyEncoders;
    }
}
