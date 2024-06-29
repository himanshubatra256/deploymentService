package com.monesto.deploymentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.monesto.deploymentService.utils.DeploymentServiceUtil;

@SpringBootApplication
public class DeploymentServiceApplication {	
	private final static Logger LOGGER = LoggerFactory.getLogger(DeploymentServiceApplication.class);
	
	public static void main(String[] args) {
		DeploymentServiceUtil.initilizeCredentials();
		SpringApplication.run(DeploymentServiceApplication.class, args);
		try {
			DeploymentServiceUtil.startDeployment();
		} catch (InterruptedException e) {
			LOGGER.error("InterruptedExpception occured while deployment. ",e);
		}
	}
}
