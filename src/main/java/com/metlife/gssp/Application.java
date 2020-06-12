package com.metlife.gssp;

import com.metlife.gssp.common.registryconfig.ServiceRegistryConfig;
import com.metlife.gssp.noscan.EIPRibbonConfig;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.cloud.netflix.ribbon.RibbonClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.ImportResource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

/**
 * Created by srupasinghe on 1/28/2016.
 *
 */
@SpringBootApplication // Support for running as Spring Boot App, auto config,
@ComponentScan(basePackages = { "${componentScan.basePackages}" })
@ImportResource({ "classpath:META-INF/configs/de.spring.base.context.xml", "classpath:META-INF/configs/event-sender-context.xml"})
@EnableEurekaClient // enable this when ready to publish your service
@EnableConfigurationProperties(ServiceRegistryConfig.class)
@EnableAspectJAutoProxy(proxyTargetClass=true)
@RefreshScope
@RibbonClients({
  @RibbonClient(name = "spiservice",
    configuration = EIPRibbonConfig.class)})
public class Application {

  public static void main(String[] args) throws Exception {
    SpringApplication.run(Application.class, args);
  }

  @Bean
	public WebMvcConfigurerAdapter corsConfigurer() {
		return new WebMvcConfigurerAdapter() {
			// as of now not used


			@Override
			public void addCorsMappings(CorsRegistry registry) {
				/**
				 * By default all origins and GET, HEAD and POST methods are
				 * allowed.
				 */
				registry.addMapping("/**").allowedMethods("GET", "PUT", "POST", "DELETE");
			}

		};
	}
}
