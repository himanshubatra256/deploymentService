package com.monesto.deploymentService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.monesto.deploymentService.utils.DeploymentServiceUtil;

@SpringBootApplication
public class DeploymentServiceApplication {	
	
	public static void main(String[] args) {
		DeploymentServiceUtil.initilizeCredentials();
		SpringApplication.run(DeploymentServiceApplication.class, args);
		DeploymentServiceUtil.startDeployment();
	}
}
