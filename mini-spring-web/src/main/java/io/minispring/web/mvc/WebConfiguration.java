package io.minispring.web.mvc;

import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.beans.BeanFactory;
import io.minispring.core.convert.ConversionService;
import io.minispring.core.env.Environment;
import io.minispring.web.http.WebServer;
import io.minispring.web.json.JsonMapper;

/**
 * Beans contributed by {@link EnableWebMvc}. Properties: {@code server.port} (default 8080, 0 picks
 * a free port) and {@code server.host} (default 0.0.0.0).
 */
@Configuration
public class WebConfiguration {

    @Bean
    public JsonMapper jsonMapper() {
        return new JsonMapper();
    }

    @Bean
    public DispatcherHandler dispatcherHandler(BeanFactory beanFactory, ConversionService conversion, JsonMapper json) {
        return new DispatcherHandler(beanFactory, conversion, json);
    }

    @Bean
    public WebServer webServer(Environment environment, DispatcherHandler dispatcher) {
        return new WebServer(environment.getProperty("server.host", "0.0.0.0"),
                environment.getProperty("server.port", Integer.class, 8080), dispatcher, dispatcher::initialize);
    }
}
