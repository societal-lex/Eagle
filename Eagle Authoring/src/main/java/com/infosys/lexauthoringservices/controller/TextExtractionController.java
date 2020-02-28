/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.infosys.lexauthoringservices.service.TextExtractionService;
import com.infosys.lexauthoringservices.util.LexConstants;

public class TextExtractionController {
	
	@Autowired
	TextExtractionService textExtractionService;
	
	@PostMapping("/action/extract/topics")
	public ResponseEntity<?> resourceTopics(@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,@RequestBody Map<String, Object> requestMap) throws Exception{
		
		Map<String, Object> responseMap = new HashMap<>();
		responseMap.put(LexConstants.IDENTIFIER, textExtractionService.resourceTextExtraction(rootOrg, org, requestMap));
		return new ResponseEntity<>(responseMap, HttpStatus.OK);
	}
	
	@PostMapping("/action/hierarchy/topics")
	public ResponseEntity<?> textBlockData(@RequestParam(value = LexConstants.ROOT_ORG, defaultValue = "Infosys") String rootOrg,
			@RequestParam(value = LexConstants.ORG, defaultValue = "Infosys Ltd") String org,@RequestBody Map<String, Object> requestMap) throws Exception{
				
		Map<String,Object> responseMap = new HashMap<>();
		responseMap.put(LexConstants.IDENTIFIER, textExtractionService.hierarchialExtraction(rootOrg,org,requestMap));
		return new ResponseEntity<>(responseMap,HttpStatus.OK);	
	}

}
