package com.monesto.deploymentService.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeploymentServiceController {
	
	/**
	 * Health check end-point to check the application's running status
	 * @return HttpStatus.OK
	 */
	@GetMapping("/health")
	public ResponseEntity<Boolean> healthCheck() {
		return ResponseEntity.status(HttpStatus.OK).body(true);
	}

}
