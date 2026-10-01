package com.emilio.streambox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StreamboxApplication {

	public static void main(String[] args) {
		SpringApplication.run(StreamboxApplication.class, args);
	}

}
