package com.paymentflow.common.autoconfigure;

import com.paymentflow.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the auto-configuration activates for servlet apps and stays inactive otherwise. */
class CommonAutoConfigurationTest {

    private static final AutoConfigurations AUTO_CONFIGS = AutoConfigurations.of(
            CorrelationIdAutoConfiguration.class, GlobalExceptionHandlerAutoConfiguration.class);

    @Test
    void registersWebBeansInServletApplication() {
        new WebApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .run(context -> {
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void doesNotRegisterWebBeansInNonWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AUTO_CONFIGS)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }
}
