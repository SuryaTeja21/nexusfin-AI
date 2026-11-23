package com.nexusfin.notification.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    authenticateStompConnect(accessor);
                } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    authorizeStompSubscribe(accessor);
                }

                return message;
            }
        });
    }

    private void authenticateStompConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            authHeader = accessor.getFirstNativeHeader("token");
        }

        if (authHeader == null || authHeader.isBlank()) {
            log.warn("STOMP CONNECT rejected: missing Authorization header");
            throw new BadCredentialsException("Missing Authorization header in STOMP CONNECT");
        }

        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader.trim();
        try {
            Jwt jwt = jwtDecoder.decode(token);
            Authentication authentication = jwtAuthenticationConverter.convert(jwt);
            accessor.setUser(authentication);
            log.info("STOMP CONNECT authenticated: user={}, roles={}", authentication.getName(), authentication.getAuthorities());
        } catch (Exception ex) {
            log.warn("STOMP CONNECT token validation failed: {}", ex.getMessage());
            throw new BadCredentialsException("Invalid JWT token: " + ex.getMessage(), ex);
        }
    }

    private void authorizeStompSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        Authentication user = (Authentication) accessor.getUser();

        if (destination == null || user == null) {
            return;
        }

        // Only ADMIN and SERVICE roles can subscribe to global broadcast destination /topic/transactions
        if (destination.startsWith("/topic/transactions")) {
            boolean isAdmin = user.getAuthorities().stream().anyMatch(a ->
                    "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()) ||
                    "ROLE_SERVICE".equalsIgnoreCase(a.getAuthority()));
            if (!isAdmin) {
                log.warn("STOMP SUBSCRIBE forbidden: user [{}] without ADMIN role attempted subscription to [{}]",
                        user.getName(), destination);
                throw new AccessDeniedException("Access denied: only ADMIN can subscribe to /topic/transactions");
            }
        }
    }
}
