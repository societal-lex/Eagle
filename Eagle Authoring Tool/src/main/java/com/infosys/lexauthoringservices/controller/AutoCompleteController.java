/*               "Copyright 2020 Infosys Ltd.
               Use of this source code is governed by GPL v3 license that can be found in the LICENSE file or at https://opensource.org/licenses/GPL-3.0
               This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3"*/
package com.infosys.lexauthoringservices.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.infosys.lexauthoringservices.exception.BadRequestException;
import com.infosys.lexauthoringservices.service.AutoCompleteService;

@RestController
@RequestMapping("/action/meta/v1")
public class AutoCompleteController {

	@Autowired
	AutoCompleteService autoCompleteService;

	@PostMapping("/units")
	public ResponseEntity<List<Map<String, Object>>> getUnitsToBeDisplayed(@RequestBody Map<String, Object> reqMap) {

		if (reqMap == null || reqMap.isEmpty())
			throw new BadRequestException("Valid Contract not adhered");

		String query = (String) reqMap.get("query");
		String limit = (String) reqMap.get("limit");

		if (query == null || query.trim().isEmpty())
			throw new BadRequestException("Query cant be null or empty");

		List<Map<String, Object>> units = autoCompleteService.getUnitsToBeDisplayed(query, limit);

		return new ResponseEntity<>(units, HttpStatus.OK);
	}

	@PostMapping("/skills")
	public ResponseEntity<List<Map<String, Object>>> getSkillsToBeDisplayed(@RequestBody Map<String, Object> reqMap) {

		if (reqMap == null || reqMap.isEmpty())
			throw new BadRequestException("Valid Contract not adhered");

		String query = (String) reqMap.get("query");
		String limit = (String) reqMap.get("limit");

		if (query == null || query.trim().isEmpty())
			throw new BadRequestException("Query cant be null or empty");

		List<Map<String, Object>> skills = autoCompleteService.getSkillsToBeDisplayed(query, limit);

		return new ResponseEntity<>(skills, HttpStatus.OK);
	}

	@PostMapping("/clients")
	public ResponseEntity<List<Map<String, Object>>> getClientsToBeDisplayed(@RequestBody Map<String, Object> reqMap) {

		if (reqMap == null || reqMap.isEmpty())
			throw new BadRequestException("Valid Contract not adhered");

		String query = (String) reqMap.get("query");
		String limit = (String) reqMap.get("limit");

		if (query == null || query.trim().isEmpty())
			throw new BadRequestException("Query cant be null or empty");

		List<Map<String, Object>> skills = autoCompleteService.getClientNamesToBeDisplayed(query, limit);

		return new ResponseEntity<>(skills, HttpStatus.OK);
	}

	@GetMapping("/ordinals/list")
	public ResponseEntity<Map<String, Object>> getEnumsToBeDisplayed() {

		Map<String, Object> enums = autoCompleteService.getEnumsToBeDisplayed();

		return new ResponseEntity<>(enums, HttpStatus.OK);
	}
}
