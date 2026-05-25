package dev.themajorones.atw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EntityScan("dev.themajorones.models.entity")
public class AtwApplication {

	public static void main(String[] args) {
		SpringApplication.run(AtwApplication.class, args);
	}

}
