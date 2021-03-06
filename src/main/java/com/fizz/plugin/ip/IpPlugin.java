package com.fizz.plugin.ip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.fizz.plugin.ip.util.ConfigUtils;
import com.fizz.plugin.ip.util.IpMatchUtils;
import com.fizz.plugin.ip.util.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import we.plugin.FizzPluginFilter;
import we.plugin.FizzPluginFilterChain;
import we.plugin.auth.ApiConfig;
import we.plugin.auth.ApiConfigService;
import we.util.WebUtils;

import javax.annotation.Resource;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.fizz.plugin.ip.IpPlugin.PLUGIN_NAME;
import static com.fizz.plugin.ip.RouterConfig.FieldName.*;

/**
 * @author hua.huang
 */
@Slf4j
@Component(value = PLUGIN_NAME)
public class IpPlugin implements FizzPluginFilter {
    public static final String PLUGIN_NAME = "fizz_plugin_ip";
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private ApiConfigService apiConfigService;

    @Override
    @SuppressWarnings("unchecked")
    public Mono<Void> filter(ServerWebExchange exchange, Map<String, Object> config) {
        RouterConfig routerConfig = routerConfig(exchange, config);
        List<PluginConfig.Item> pluginConfigItemList = pluginConfig(exchange, config);
        if (access(exchange, routerConfig, pluginConfigItemList)) {
            log.trace("pass...");
            return FizzPluginFilterChain.next(exchange);
        }
        log.trace("forbidden!");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, routerConfig.getErrorRespContentType());
        return WebUtils.buildDirectResponse(exchange, HttpStatus.FORBIDDEN,
                headers, routerConfig.getErrorRespContent());
    }

    private boolean access(ServerWebExchange exchange,
                           RouterConfig routerConfig, List<PluginConfig.Item> pluginConfigItemList) {
        Set<String> fixedWhiteIpSet = Sets.newHashSet();
        Set<String> fixedBlackIpSet = Sets.newHashSet();
        ApiConfig apiConfig = apiConfig(exchange);
        Set<String> gatewayGroups = (apiConfig == null || apiConfig.gatewayGroups == null) ? Sets.newHashSet() : apiConfig.gatewayGroups;
        if (!CollectionUtils.isEmpty(pluginConfigItemList)) {
            for (PluginConfig.Item fixedConfigItem : pluginConfigItemList) {
                if (gatewayGroups.contains(fixedConfigItem.getGwGroup())) {
                    fixedWhiteIpSet.addAll(IpMatchUtils.ipConfigList(fixedConfigItem.getWhiteIp()));
                    fixedBlackIpSet.addAll(IpMatchUtils.ipConfigList(fixedConfigItem.getBlackIp()));
                }
            }
        }
        Set<String> whiteIpSet = ConfigUtils.string2set(routerConfig.getWhiteIp());
        Set<String> blackIpSet = ConfigUtils.string2set(routerConfig.getBlackIp());

        String ip = null;
        try {
            ip = IpUtils.getServerHttpRequestIp(exchange.getRequest());
        } catch (SocketException e) {
            log.warn(e.getMessage(), e);
        }
        log.trace("clientIp:{}, fixedWhiteIpSet:{}, fixedBlackIpSet:{}, whiteIpSet:{}, blackIpSet:{}",
                ip, fixedWhiteIpSet, fixedBlackIpSet, whiteIpSet, blackIpSet);
        // ????????????client ip?????????false
        if (StringUtils.isBlank(ip)) {
            return false;
        }

        // ??????????????????????????????????????????????????????????????????

        // ???????????????????????????????????????????????????true
        if (IpMatchUtils.match(ip, whiteIpSet)) {
            return true;
        }
        // ???????????????????????????????????????????????????false
        if (IpMatchUtils.match(ip, blackIpSet)) {
            return false;
        }
        // ???????????????????????????????????????????????????true
        if (IpMatchUtils.match(ip, fixedWhiteIpSet)) {
            return true;
        }
        // ???????????????????????????????????????????????????false
        if (IpMatchUtils.match(ip, fixedBlackIpSet)) {
            return false;
        }
        // ??????????????????????????????????????????
        if (CollectionUtils.isEmpty(whiteIpSet) || CollectionUtils.isEmpty(fixedWhiteIpSet)) {
            // ???????????????????????????????????????true
            return true;
        } else {
            return false;
        }
    }

    private RouterConfig routerConfig(ServerWebExchange exchange, Map<String, Object> config) {
        RouterConfig routerConfig = new RouterConfig();
        routerConfig.setErrorRespContentType((String) config.getOrDefault(ERROR_RESP_CONTENT_TYPE
                , routerConfig.getErrorRespContentType()));
        routerConfig.setErrorRespContent((String) config.getOrDefault(ERROR_RESP_CONTENT
                , routerConfig.getErrorRespContent()));
        routerConfig.setWhiteIp((String) config.getOrDefault(WHITE_IP, StringUtils.EMPTY));
        routerConfig.setBlackIp((String) config.getOrDefault(BLACK_IP, StringUtils.EMPTY));
        return routerConfig;
    }

    private List<PluginConfig.Item> pluginConfig(ServerWebExchange exchange, Map<String, Object> config) {
        String fixedConfig = (String) config.get(we.plugin.PluginConfig.CUSTOM_CONFIG);
        try {
            PluginConfig pluginConfig = objectMapper.readValue(fixedConfig, PluginConfig.class);
            if (pluginConfig != null && pluginConfig.getConfigs() != null) {
                return pluginConfig.getConfigs();
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return Lists.newArrayList();
    }

    private ApiConfig apiConfig(ServerWebExchange exchange) {
        ServerHttpRequest req = exchange.getRequest();
        return apiConfigService.getApiConfig(WebUtils.getAppId(exchange),
                WebUtils.getClientService(exchange), req.getMethod(), WebUtils.getClientReqPath(exchange));
    }

}
