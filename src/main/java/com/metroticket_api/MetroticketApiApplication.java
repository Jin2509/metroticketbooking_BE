package com.metroticket_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan  // tự tìm và đăng ký mọi record
public class MetroticketApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(MetroticketApiApplication.class, args);
	}

}
