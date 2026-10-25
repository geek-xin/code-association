package com.geek.association;

import com.geek.association.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class CodeAssociationApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CodeAssociationApplication.class);
        boolean headless = isHeadlessAnalyze(args);
        if (headless) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (headless) {
            // CLI mode: the exit code is the pipeline signal, so the JVM must terminate here.
            System.exit(SpringApplication.exit(context));
        }
    }

    /** The analyze sub-command runs the engine without the web server, for CI pipelines. */
    private static boolean isHeadlessAnalyze(String[] args) {
        for (String arg : args) {
            if ("analyze".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
