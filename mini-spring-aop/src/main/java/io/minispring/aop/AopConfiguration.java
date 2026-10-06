package io.minispring.aop;

import io.minispring.aop.aspects.MethodTimings;
import io.minispring.aop.aspects.Retry;
import io.minispring.aop.aspects.RetryInterceptor;
import io.minispring.aop.aspects.Timed;
import io.minispring.aop.aspects.TimingInterceptor;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.beans.BeanFactory;

/** Beans contributed by {@link EnableAspects}. Additional aspects are just more {@link Advisor} beans. */
@Configuration
public class AopConfiguration {

    @Bean
    public AutoProxyCreator autoProxyCreator(BeanFactory beanFactory) {
        return new AutoProxyCreator(beanFactory);
    }

    @Bean
    public MethodTimings methodTimings() {
        return new MethodTimings();
    }

    @Bean
    public Advisor timedAdvisor(MethodTimings timings) {
        return Advisor.forAnnotation(Timed.class, new TimingInterceptor(timings), Advisor.TIMED_ORDER);
    }

    @Bean
    public Advisor retryAdvisor() {
        return Advisor.forAnnotation(Retry.class, new RetryInterceptor(), Advisor.RETRY_ORDER);
    }
}
