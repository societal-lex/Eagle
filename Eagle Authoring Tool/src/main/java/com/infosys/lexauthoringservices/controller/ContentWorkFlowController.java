/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.infosys.lexauthoringservices.model.Response;
import com.infosys.lexauthoringservices.service.ContentWorkFlowService;

@RestController
@RequestMapping("/action/content")
public class ContentWorkFlowController {

	@Autowired
	ContentWorkFlowService contentWorkFlowService;
	
	@PostMapping("/workflow")
	public ResponseEntity<Response> addToContentWorkFlow(@RequestBody Map<String, Object> requestBody)
			throws Exception {
		return new ResponseEntity<>(contentWorkFlowService.upsertNewWorkFlow(requestBody), HttpStatus.OK);
	}

	@GetMapping("/workflow")
	public ResponseEntity<Response> FetchFromContentWorkFlow(@RequestBody Map<String, Object> requestBody)
			throws Exception {
		return new ResponseEntity<>(contentWorkFlowService.fetchWorkFlowData(requestBody), HttpStatus.OK);
	}

	@DeleteMapping("/workflow")
	public ResponseEntity<Response> removeFromContentWorkFlow(@RequestBody Map<String, Object> requestBody)
			throws Exception {
		return new ResponseEntity<>(contentWorkFlowService.removeFromWorkFlow(requestBody), HttpStatus.OK);
	}
	
	
}
